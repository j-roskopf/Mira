package com.joetr.mira.data.entries.media

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.joetr.mira.data.entries.EntryRepository
import com.joetr.mira.data.entries.decodeAttachments
import com.joetr.mira.db.Media_blobs
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.AttachmentState
import com.joetr.mira.media.MediaSource
import com.joetr.mira.media.extractVideoPoster
import com.joetr.mira.media.isHevcVideo
import com.joetr.mira.media.needsVideoPosterRotationRepair
import com.joetr.mira.media.normalizeVideo
import com.joetr.mira.media.supportsStreamingFileUpload
import com.joetr.mira.media.supportsVideoNormalization
import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.network.StorageUpload
import com.joetr.mira.network.UploadBody
import com.joetr.mira.platform.currentTimeMillis
import com.joetr.mira.platform.detectImageMimeType
import com.joetr.mira.platform.isJpegImage
import com.joetr.mira.platform.resizeImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where the archive should read an attachment from.
 *
 * [LocalFile] lets a large recording stream from disk instead of being materialised as a
 * `ByteArray`, which is what exhausted the heap when a long video was archived. [TooLarge] is
 * returned before any read so a file the repository cannot accept never reaches the heap.
 */
public sealed interface ArchiveMediaSource {
    public data class LocalFile(val path: String, val bytes: Long) : ArchiveMediaSource
    public data class Bytes(val bytes: ByteArray) : ArchiveMediaSource
    public data class TooLarge(val bytes: Long) : ArchiveMediaSource
}

/** How much local media a platform keeps once it is safely in Storage. */
public sealed interface RetentionPolicy {
    /** Desktop: a complete offline archive. Disk is cheap and nothing evicts it. */
    public data object KeepAll : RetentionPolicy

    /** Mobile: keep what fits, evict least-recently-used first. Pinned years are exempt. */
    public data class Capped(val capBytes: Long) : RetentionPolicy

    /**
     * Web: no local media at all.
     *
     * Not a preference — browsers grant OPFS quota as a fraction of free disk and evict it without
     * warning, so a "local copy" there could vanish silently.
     */
    public data object StreamOnly : RetentionPolicy
}

/**
 * Local media bytes: staging before upload, cache afterwards.
 *
 * The invariant that makes eviction safe: **once Storage confirms an upload, the local copy is a
 * cache and never the only copy.** Anything evicted re-streams on demand.
 */
public class MediaRepository(
    private val database: MiraDatabase,
    private val firebase: MiraFirebase,
    private val entries: EntryRepository,
    private val policy: RetentionPolicy,
) {
    private val queries get() = database.entriesQueries

    /**
     * Posters extracted this session, keyed by attachment id.
     *
     * Extracting a frame is cheap but not free, and a gallery can ask for the same poster several
     * times while scrolling. Coil caches the decoded bitmap; this avoids re-decoding the video.
     */
    private val posterCache = mutableMapOf<String, ByteArray>()
    private val posterCacheMutex = Mutex()

    /** Records freshly captured bytes against an entry, before any upload is attempted. */
    public suspend fun stage(
        entryId: String,
        attachmentId: String,
        path: String?,
        bytes: ByteArray?,
        size: Long,
    ) {
        val now = currentTimeMillis()
        io {
            queries.insertBlob(
                id = attachmentId,
                entry_id = entryId,
                // Capture files belong to the recorder and may be deleted on release.
                path = path?.let { preserveLocalFile(it) },
                data_ = bytes,
                bytes = size,
                uploaded = 0L,
                pinned = 0L,
                last_access = now,
                created_at = now,
            )
        }
    }

    /**
     * Uploads everything still staged, then rewrites the owning entries with the resulting
     * storage paths.
     *
     * Audio goes immediately; video waits for an unmetered network unless [includeVideo] is set,
     * because a 1080p minute is 60–100 MB and a journal is forever.
     */
    public suspend fun uploadPending(ownerId: String, includeVideo: Boolean): Int {
        val session = firebase.sessionForSync() ?: return 0
        // One-time repair for video recorded while HEVC capture was briefly enabled. Requeue the
        // local originals so the normal upload path re-encodes them to H.264 for every client.
        // A failure here is a repair failure, not a reason to skip the uploads below.
        if (supportsVideoNormalization) {
            try {
                requeueNonPortableVideos()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Retried on the next run.
            }
        }
        val staged = io { queries.selectUnuploadedBlobs().awaitAsList() }
        var uploaded = 0

        for (blob in staged) {
            // One bad attachment must not stop the rest. Anything that fails stays unuploaded in
            // the outbox and is retried on the next sync.
            val didUpload = try {
                val entry = entries.entry(blob.entry_id) ?: continue
                val attachment = entry.attachments.firstOrNull { it.id == blob.id } ?: continue
                if (attachment.kind == AttachmentKind.VIDEO && !includeVideo) continue

                val path = blob.path?.takeIf { localFileExists(it) }
                val payload = uploadPayloadFor(attachment, blob, path) ?: continue
                val objectName = firebase.storage.mediaPath(
                    ownerId = ownerId,
                    entryId = entry.id,
                    attachmentId = attachment.id,
                    extension = extensionFor(payload.mime),
                )
                val result = try {
                    firebase.storage.upload(session.idToken, objectName, payload.body, payload.mime)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: continue

                // A poster or thumbnail is optional: failing to generate or upload one must not
                // cost the recording its place in the cloud.
                val poster = try {
                    ensurePosterUploaded(
                        idToken = session.idToken,
                        ownerId = ownerId,
                        entry = entry,
                        attachment = attachment,
                        localPath = path,
                        localBytes = payload.bytes,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val thumbnail = try {
                    ensureThumbnailUploaded(
                        idToken = session.idToken,
                        ownerId = ownerId,
                        entry = entry,
                        attachment = attachment,
                        localBytes = payload.bytes,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }

                val updated = entry.copy(
                    attachments = entry.attachments.map {
                        if (it.id != attachment.id) {
                            it
                        } else {
                            it.copy(
                                storagePath = result.storagePath,
                                url = result.downloadUrl,
                                mime = payload.mime,
                                posterPath = poster?.storagePath ?: it.posterPath,
                                posterUrl = poster?.downloadUrl ?: it.posterUrl,
                                thumbnailPath = thumbnail?.storagePath ?: it.thumbnailPath,
                                thumbnailUrl = thumbnail?.downloadUrl ?: it.thumbnailUrl,
                                state = AttachmentState.UPLOADED,
                            )
                        }
                    },
                )
                entries.save(updated)
                io { queries.markBlobUploaded(blob.id) }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (didUpload) uploaded++
        }

        // Videos uploaded before posters existed (and any whose poster upload failed while the
        // video itself succeeded) get one more chance while their local copy is still around.
        if (includeVideo) {
            try {
                backfillPosters(session.idToken, ownerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Retried on the next run; the throttle below keeps it from being every run.
            }
            if (needsVideoPosterRotationRepair) {
                try {
                    repairMisrotatedPosters(session.idToken, ownerId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Retried on the next run.
                }
            }
        }
        // Photos aren't gated behind includeVideo, so neither is their thumbnail backfill.
        try {
            backfillThumbnails(session.idToken, ownerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Retried on the next run; the throttle below keeps it from being every run.
        }

        // Eviction is housekeeping; never let it fail an otherwise successful upload run.
        if (uploaded > 0) {
            try {
                enforceRetention()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the bytes; the next run will try again.
            }
        }
        return uploaded
    }

    /**
     * Where a player should read this attachment from.
     *
     * Prefers a local file so playback starts instantly and works offline, falls back to a signed
     * Storage URL so it streams, and only materialises bytes when neither is available — which is
     * the web case, and anything captured but not yet uploaded. Images use the same order so Coil
     * can fetch and disk-cache the URL rather than re-downloading bytes itself.
     */
    public suspend fun sourceFor(attachment: Attachment): MediaSource? {
        val blob = io { queries.selectBlob(attachment.id).awaitAsOneOrNull() }
        if (blob != null) {
            io { queries.touchBlob(currentTimeMillis(), attachment.id) }
            blob.path?.let { path ->
                if (localFileExists(path)) return MediaSource.LocalFile(path)
            }
            blob.data_?.let { bytes -> return MediaSource.Bytes(bytes, attachment.mime) }
        }
        // Pending media from another device has neither local bytes nor a Storage object yet.
        if (attachment.storagePath.isBlank() && attachment.url == null) return null
        // Images are rendered through Coil, which can fetch and disk-cache a Storage URL. Prefer
        // that over materialising the bytes here so revisiting an evicted photo does not download
        // it again into memory on every open. Only read the bytes when no URL is available yet
        // (not uploaded) or the download token is missing.
        if (attachment.kind == AttachmentKind.IMAGE) {
            attachment.url?.let { return MediaSource.Remote(it) }
            bytesFor(attachment)?.let { return MediaSource.Bytes(it, attachment.mime) }
        }

        attachment.url?.let { return MediaSource.Remote(it) }

        // No local copy and no usable URL: fetch it so playback can still happen.
        val bytes = bytesFor(attachment) ?: return null
        return MediaSource.Bytes(bytes, attachment.mime)
    }

    /**
     * Where a thumbnail should read this attachment from.
     *
     * A video has no thumbnail itself, so this returns its cover frame: extracted from the local
     * copy when there is one, otherwise the small poster uploaded alongside the video. An image's
     * local copy is already on-device at no network cost, so that is still used directly; once it
     * is evicted or lives on another device, this prefers the small uploaded thumbnail over the
     * full-resolution original — a 60dp tile has no reason to pull a multi-megabyte 4096px photo
     * over the network.
     */
    public suspend fun thumbnailSourceFor(attachment: Attachment): MediaSource? =
        when (attachment.kind) {
            AttachmentKind.VIDEO -> posterSourceFor(attachment)
            AttachmentKind.IMAGE -> imageThumbnailSourceFor(attachment)
            AttachmentKind.AUDIO -> sourceFor(attachment)
        }

    private suspend fun imageThumbnailSourceFor(attachment: Attachment): MediaSource? {
        val blob = io { queries.selectBlob(attachment.id).awaitAsOneOrNull() }
        if (blob != null) {
            io { queries.touchBlob(currentTimeMillis(), attachment.id) }
            blob.path?.let { path ->
                if (localFileExists(path)) return MediaSource.LocalFile(path)
            }
            blob.data_?.let { bytes -> return MediaSource.Bytes(bytes, attachment.mime) }
        }
        if (attachment.storagePath.isBlank() && attachment.thumbnailUrl == null && attachment.url == null) {
            return null
        }
        attachment.thumbnailUrl?.let { return MediaSource.Remote(it) }
        attachment.url?.let { return MediaSource.Remote(it) }
        val bytes = bytesFor(attachment) ?: return null
        return MediaSource.Bytes(bytes, attachment.mime)
    }

    private suspend fun posterSourceFor(attachment: Attachment): MediaSource? {
        posterCacheGet(attachment.id)?.let { return MediaSource.Bytes(it, "image/jpeg") }

        val blob = io { queries.selectBlob(attachment.id).awaitAsOneOrNull() }
        if (blob != null) {
            io { queries.touchBlob(currentTimeMillis(), attachment.id) }
            val path = blob.path?.takeIf { localFileExists(it) }
            if (path != null || blob.data_ != null) {
                val poster = runCatching {
                    extractVideoPoster(
                        path = path,
                        bytes = if (path == null) blob.data_ else null,
                        mime = attachment.mime,
                    )
                }.getOrNull()
                if (poster != null) {
                    posterCachePut(attachment.id, poster)
                    return MediaSource.Bytes(poster, "image/jpeg")
                }
            }
        }

        // A poster is tiny and Coil disk-caches it, so this is the cheap path for a video whose
        // bytes were evicted or that was captured on another device.
        if (attachment.posterUrl == null && attachment.posterPath == null) return null
        attachment.posterUrl?.let { return MediaSource.Remote(it) }
        val posterPath = attachment.posterPath ?: return null
        val session = firebase.sessionForSync() ?: return null
        val downloaded = runCatching {
            firebase.storage.download(session.idToken, posterPath)
        }.getOrNull() ?: return null
        posterCachePut(attachment.id, downloaded)
        return MediaSource.Bytes(downloaded, "image/jpeg")
    }

    private suspend fun posterCacheGet(attachmentId: String): ByteArray? =
        posterCacheMutex.withLock { posterCache[attachmentId] }

    private suspend fun posterCachePut(attachmentId: String, bytes: ByteArray) {
        posterCacheMutex.withLock { posterCache[attachmentId] = bytes }
    }

    /**
     * Generates and uploads a cover frame for [attachment] when it does not have one yet.
     *
     * Returns null when the platform cannot extract a frame or the upload fails; callers treat a
     * missing poster as cosmetic, never as a failed attachment.
     */
    private suspend fun ensurePosterUploaded(
        idToken: String,
        ownerId: String,
        entry: com.joetr.mira.domain.Entry,
        attachment: Attachment,
        localPath: String?,
        localBytes: ByteArray?,
        force: Boolean = false,
    ): StorageUpload? {
        if (attachment.kind != AttachmentKind.VIDEO) return null
        if (!force && attachment.posterPath != null && attachment.posterUrl != null) return null

        // A forced repair must not reuse the cache: it may hold the previous, misrotated poster.
        val cached = if (force) null else posterCacheGet(attachment.id)
        val poster = cached ?: run {
            val path = localPath?.takeIf { localFileExists(it) }
            val extracted = runCatching {
                extractVideoPoster(
                    path = path,
                    bytes = if (path == null) localBytes else null,
                    mime = attachment.mime,
                )
            }.getOrNull() ?: return null
            posterCachePut(attachment.id, extracted)
            extracted
        }
        val objectName = firebase.storage.mediaPath(
            ownerId = ownerId,
            entryId = entry.id,
            attachmentId = attachment.id,
            extension = "jpg",
        )
        return firebase.storage.upload(idToken, objectName, poster, "image/jpeg")
    }

    /**
     * Generates and uploads a small preview JPEG for [attachment] when it does not have one yet.
     *
     * Returns null when resizing or the upload fails; callers treat a missing thumbnail as
     * cosmetic, never as a failed attachment — [thumbnailSourceFor] falls back to the full image.
     */
    private suspend fun ensureThumbnailUploaded(
        idToken: String,
        ownerId: String,
        entry: com.joetr.mira.domain.Entry,
        attachment: Attachment,
        localBytes: ByteArray?,
    ): StorageUpload? {
        if (attachment.kind != AttachmentKind.IMAGE) return null
        if (attachment.thumbnailPath != null && attachment.thumbnailUrl != null) return null
        val source = localBytes ?: return null

        val thumbnail = runCatching {
            resizeImage(source, PHOTO_THUMBNAIL_MAX_LONG_EDGE, PHOTO_THUMBNAIL_JPEG_QUALITY)
        }.getOrNull() ?: return null
        val objectName = firebase.storage.mediaPath(
            ownerId = ownerId,
            entryId = entry.id,
            attachmentId = "${attachment.id}_thumb",
            extension = "jpg",
        )
        return firebase.storage.upload(idToken, objectName, thumbnail, "image/jpeg")
    }

    /**
     * Uploads thumbnails for photos that were captured before thumbnails existed.
     *
     * Only local copies can be used — downloading the full original back just to shrink it is the
     * expense this feature exists to avoid. Throttled so a missing local copy is not retried on
     * every sync.
     */
    private suspend fun backfillThumbnails(idToken: String, ownerId: String) {
        val now = currentTimeMillis()
        val key = "$THUMBNAIL_BACKFILL_KEY:$ownerId"
        val lastAttempt = io { queries.getSetting(key).awaitAsOneOrNull() }?.toLongOrNull() ?: 0L
        if (now - lastAttempt < THUMBNAIL_BACKFILL_INTERVAL_MS) return
        io { queries.putSetting(key, now.toString()) }

        for (entry in entries.entriesForOwner(ownerId)) {
            var changed = false
            val attachments = entry.attachments.map { attachment ->
                if (attachment.kind != AttachmentKind.IMAGE) return@map attachment
                if (attachment.thumbnailPath != null && attachment.thumbnailUrl != null) {
                    return@map attachment
                }

                val blob = io { queries.selectBlob(attachment.id).awaitAsOneOrNull() }
                    ?: return@map attachment
                val bytes = blob.data_ ?: blob.path?.takeIf { localFileExists(it) }?.let {
                    readLocalFile(it)
                } ?: return@map attachment

                val thumbnail = runCatching {
                    ensureThumbnailUploaded(
                        idToken = idToken,
                        ownerId = ownerId,
                        entry = entry,
                        attachment = attachment,
                        localBytes = bytes,
                    )
                }.getOrNull() ?: return@map attachment

                changed = true
                attachment.copy(
                    thumbnailPath = thumbnail.storagePath,
                    thumbnailUrl = thumbnail.downloadUrl,
                )
            }
            if (changed) entries.save(entry.copy(attachments = attachments))
        }
    }

    /**
     * Uploads poster frames for videos that were captured before posters existed.
     *
     * Only local copies can be used — downloading the video back to extract one frame is the
     * expense this feature exists to avoid. Throttled so an undecodable file is not retried on
     * every sync.
     */
    private suspend fun backfillPosters(idToken: String, ownerId: String) {
        val now = currentTimeMillis()
        val key = "$POSTER_BACKFILL_KEY:$ownerId"
        val lastAttempt = io { queries.getSetting(key).awaitAsOneOrNull() }?.toLongOrNull() ?: 0L
        if (now - lastAttempt < POSTER_BACKFILL_INTERVAL_MS) return
        io { queries.putSetting(key, now.toString()) }

        for (entry in entries.entriesForOwner(ownerId)) {
            var changed = false
            val attachments = entry.attachments.map { attachment ->
                if (attachment.kind != AttachmentKind.VIDEO) return@map attachment
                if (attachment.posterPath != null && attachment.posterUrl != null) return@map attachment

                val blob = io { queries.selectBlob(attachment.id).awaitAsOneOrNull() }
                    ?: return@map attachment
                val path = blob.path?.takeIf { localFileExists(it) }
                if (path == null && blob.data_ == null) return@map attachment

                val poster = runCatching {
                    ensurePosterUploaded(
                        idToken = idToken,
                        ownerId = ownerId,
                        entry = entry,
                        attachment = attachment,
                        localPath = path,
                        localBytes = blob.data_,
                    )
                }.getOrNull() ?: return@map attachment

                changed = true
                attachment.copy(
                    posterPath = poster.storagePath,
                    posterUrl = poster.downloadUrl,
                )
            }
            if (changed) entries.save(entry.copy(attachments = attachments))
        }
    }

    /**
     * Rebuilds posters that a pre-fix Android build extracted with the wrong orientation.
     *
     * `MediaMetadataRetriever` already applies the video's rotation, and the old extractor rotated
     * the frame a second time, so portrait recordings ended up as landscape covers. This runs once
     * per install, only for videos whose local copy is still around; a missing local copy is left
     * to the normal poster backfill on a device that still has the file.
     */
    private suspend fun repairMisrotatedPosters(idToken: String, ownerId: String) {
        val done = io { queries.getSetting(POSTER_ROTATION_REPAIR_KEY).awaitAsOneOrNull() }
        if (done == "1") return

        for (entry in entries.entriesForOwner(ownerId)) {
            var changed = false
            val attachments = entry.attachments.map { attachment ->
                if (attachment.kind != AttachmentKind.VIDEO) return@map attachment

                val blob = io { queries.selectBlob(attachment.id).awaitAsOneOrNull() }
                    ?: return@map attachment
                val path = blob.path?.takeIf { localFileExists(it) }
                if (path == null && blob.data_ == null) return@map attachment

                val poster = runCatching {
                    ensurePosterUploaded(
                        idToken = idToken,
                        ownerId = ownerId,
                        entry = entry,
                        attachment = attachment,
                        localPath = path,
                        localBytes = blob.data_,
                        force = true,
                    )
                }.getOrNull() ?: return@map attachment

                changed = true
                attachment.copy(
                    posterPath = poster.storagePath,
                    posterUrl = poster.downloadUrl,
                )
            }
            if (changed) entries.save(entry.copy(attachments = attachments))
        }
        io { queries.putSetting(POSTER_ROTATION_REPAIR_KEY, "1") }
    }

    /** Bytes for playback: local if cached, otherwise streamed from Storage. */
    public suspend fun bytesFor(attachment: Attachment): ByteArray? {
        readBytes(attachment.id)?.let {
            io { queries.touchBlob(currentTimeMillis(), attachment.id) }
            return it
        }
        val session = firebase.sessionForSync() ?: return null
        return runCatching {
            firebase.storage.download(session.idToken, attachment.storagePath)
        }.getOrNull()
    }

    /**
     * Resolves the cheapest way to hand [attachment] to the archive.
     *
     * A local file is returned as a path on platforms that can stream it, so archiving a long
     * recording never allocates its full size. Media above [maxBytes] is rejected before reading;
     * the GitHub blob API cannot accept a file that large anyway.
     */
    public suspend fun archiveMediaSourceFor(
        attachment: Attachment,
        maxBytes: Long,
    ): ArchiveMediaSource? {
        val blob = io { queries.selectBlob(attachment.id).awaitAsOneOrNull() }
        val size = blob?.bytes?.takeIf { it > 0 } ?: attachment.bytes
        if (size > maxBytes) return ArchiveMediaSource.TooLarge(size)
        if (blob != null) {
            val path = blob.path
            if (path != null && localFileExists(path)) {
                return if (supportsStreamingFileUpload) {
                    ArchiveMediaSource.LocalFile(path, size)
                } else {
                    readLocalFile(path)?.let { ArchiveMediaSource.Bytes(it) }
                }
            }
            blob.data_?.let { return ArchiveMediaSource.Bytes(it) }
        }
        return bytesFor(attachment)?.let { ArchiveMediaSource.Bytes(it) }
    }

    /** Keeps a year permanently local, exempt from eviction. */
    public suspend fun setPinned(attachmentId: String, pinned: Boolean) {
        io { queries.setBlobPinned(if (pinned) 1L else 0L, attachmentId) }
    }

    public suspend fun localBytesUsed(): Long = io {
        queries.totalBlobBytes().awaitAsOneOrNull() ?: 0L
    }

    /**
     * Attachments on live entries whose bytes have not reached Storage yet.
     *
     * A blob can outlive the attachment that referenced it — removing media from an entry leaves
     * the staged bytes behind. Those rows still point at the entry, so counting rows alone would
     * keep reporting them as waiting forever even though the upload path skips them. Count only
     * blobs the entry itself still lists, which is exactly what [uploadPending] will try.
     */
    public suspend fun pendingUploadCount(): Int = io {
        queries.selectUnuploadedBlobs().awaitAsList().count { blob ->
            queries.selectEntry(blob.entry_id).awaitAsOneOrNull()
                ?.let { entry -> decodeAttachments(entry.attachments).any { it.id == blob.id } }
                ?: false
        }
    }

    /**
     * Drops staged uploads the entry no longer references, e.g. media removed in the editor before
     * saving. Only called with an authoritative attachment list, so an attachment still being
     * staged for an in-progress edit is never mistaken for a stale one.
     */
    public suspend fun pruneUnreferencedStaged(entryId: String, attachments: List<Attachment>) {
        val referenced = attachments.mapTo(mutableSetOf()) { it.id }
        val staged = io { queries.selectBlobsForEntry(entryId).awaitAsList() }
        for (blob in staged) {
            if (blob.uploaded != 0L || blob.id in referenced) continue
            blob.path?.let { deletePreservedFile(it) }
            io { queries.deleteBlob(blob.id) }
        }
    }

    /**
     * Removes staged uploads that no live entry references, left behind by abandoned edits or
     * media removed before this cleanup existed. Safe at startup because no editor is staging
     * attachments yet.
     */
    public suspend fun pruneAllUnreferencedStaged() {
        val staged = io { queries.selectUnuploadedBlobs().awaitAsList() }
        if (staged.isEmpty()) return
        val referencedByEntry = mutableMapOf<String, Set<String>>()
        for (blob in staged) {
            val entry = io { queries.selectEntry(blob.entry_id).awaitAsOneOrNull() } ?: continue
            val referenced = referencedByEntry.getOrPut(blob.entry_id) {
                decodeAttachments(entry.attachments).mapTo(mutableSetOf()) { it.id }
            }
            if (blob.id in referenced) continue
            blob.path?.let { deletePreservedFile(it) }
            io { queries.deleteBlob(blob.id) }
        }
    }

    /** Deletes every locally cached or staged media file, including unattached captures. */
    public suspend fun clearLocalMedia() {
        io {
            queries.selectAllBlobs().awaitAsList().forEach { blob ->
                blob.path?.let { deletePreservedFile(it) }
            }
            queries.deleteAllMediaBlobs()
        }
    }

    /**
     * Deletes an entry's media from Storage and locally.
     *
     * With no Cloud Functions nothing server-side reclaims these, so the client does it eagerly —
     * and [findOrphans] exists for the case where it could not.
     */
    public suspend fun deleteMediaFor(entryId: String, attachments: List<Attachment>) {
        val session = firebase.sessionForSync()
        if (session != null) {
            for (attachment in attachments) {
                runCatching {
                    firebase.storage.deleteObject(session.idToken, attachment.storagePath)
                }
                attachment.posterPath?.let { poster ->
                    runCatching { firebase.storage.deleteObject(session.idToken, poster) }
                }
                attachment.thumbnailPath?.let { thumbnail ->
                    runCatching { firebase.storage.deleteObject(session.idToken, thumbnail) }
                }
            }
        }
        for (attachment in attachments) {
            val blob = io { queries.selectBlob(attachment.id).awaitAsOneOrNull() }
            blob?.path?.let { deletePreservedFile(it) }
        }
        io { queries.deleteBlobsForEntry(entryId) }
    }

    /**
     * Storage objects with no referencing entry.
     *
     * The known weak point of the zero-backend design: if a delete happened while offline and the
     * queued cleanup never completed, the bytes stay in the bucket forever. Settings surfaces this.
     */
    public suspend fun findOrphans(ownerId: String): List<String> {
        val session = firebase.sessionForSync() ?: return emptyList()
        val remote = runCatching {
            firebase.storage.list(session.idToken, "users/$ownerId/media/")
        }.getOrNull() ?: return emptyList()

        val referenced = io {
            queries.selectAllEntries(ownerId).awaitAsList()
        }.flatMap { row ->
            com.joetr.mira.data.entries.decodeAttachments(row.attachments)
        }.flatMap { listOfNotNull(it.storagePath, it.posterPath, it.thumbnailPath) }.toSet()

        return remote.filterNot { it in referenced }
    }

    public suspend fun deleteOrphans(objectNames: List<String>): Int {
        val session = firebase.sessionForSync() ?: return 0
        var removed = 0
        for (name in objectNames) {
            if (runCatching { firebase.storage.deleteObject(session.idToken, name) }.isSuccess) {
                removed++
            }
        }
        return removed
    }

    /** Evicts uploaded, unpinned blobs oldest-first until the cache fits the policy. */
    public suspend fun enforceRetention() {
        when (policy) {
            RetentionPolicy.KeepAll -> return
            RetentionPolicy.StreamOnly -> {
                io {
                    queries.selectEvictionCandidates().awaitAsList().forEach {
                        it.path?.let { path -> deletePreservedFile(path) }
                        queries.deleteBlob(it.id)
                    }
                }
            }
            is RetentionPolicy.Capped -> {
                var used = localBytesUsed()
                if (used <= policy.capBytes) return
                val candidates = io { queries.selectEvictionCandidates().awaitAsList() }
                for (blob in candidates) {
                    if (used <= policy.capBytes) break
                    blob.path?.let { deletePreservedFile(it) }
                    io { queries.deleteBlob(blob.id) }
                    used -= blob.bytes
                }
            }
        }
    }

    /**
     * Re-marks already-uploaded HEVC videos as pending so they are uploaded again as H.264.
     *
     * Earlier builds could record HEVC on Apple hardware. The cloud copy is the source for every
     * other device, so a device that still holds the original has to republish it in a codec
     * everyone can decode. Runs once per install; platforms without a transcoder skip it entirely.
     */
    private suspend fun requeueNonPortableVideos() {
        val done = io { queries.getSetting(NON_PORTABLE_VIDEO_REPAIR_KEY).awaitAsOneOrNull() }
        if (done == "1") return
        for (blob in io { queries.selectAllBlobs().awaitAsList() }) {
            if (blob.uploaded == 0L) continue
            val entry = entries.entry(blob.entry_id) ?: continue
            val attachment = entry.attachments.firstOrNull { it.id == blob.id } ?: continue
            if (attachment.kind != AttachmentKind.VIDEO) continue
            val bytes = readBytes(blob.id) ?: continue
            if (isHevcVideo(bytes)) io { queries.markBlobPending(blob.id) }
        }
        io { queries.putSetting(NON_PORTABLE_VIDEO_REPAIR_KEY, "1") }
    }

    /**
     * Chooses how to hand one attachment's bytes to Storage.
     *
     * Video that the platform cannot transcode is streamed straight from its preserved file where
     * possible. Reading a long recording into a `ByteArray` first is what exhausted the Android
     * heap and killed the app mid-upload.
     *
     * Images are normalised in memory: phones hand back HEIC from the camera roll, and HEIC is not
     * viewable in the browser or on every desktop decoder. [resizeImage] uses each platform's own
     * image stack (and heic2any on web) to bake orientation and produce a JPEG every client can
     * show. Video is re-encoded only where [normalizeVideo] has an encoder; the local copy keeps
     * its original bytes either way.
     */
    private suspend fun uploadPayloadFor(
        attachment: Attachment,
        blob: Media_blobs,
        localPath: String?,
    ): UploadPayload? {
        if (attachment.kind == AttachmentKind.VIDEO) {
            if (supportsStreamingFileUpload && !supportsVideoNormalization && localPath != null) {
                return UploadPayload(
                    body = UploadBody.LocalFile(localPath),
                    mime = attachment.mime,
                    bytes = null,
                )
            }
            val bytes = readBytes(blob.id) ?: return null
            val portable = runCatching { normalizeVideo(bytes, attachment.mime) }.getOrNull()
            val uploaded = portable ?: bytes
            return UploadPayload(UploadBody.Bytes(uploaded), attachment.mime, uploaded)
        }

        val bytes = readBytes(blob.id) ?: return null
        if (attachment.kind != AttachmentKind.IMAGE) {
            return UploadPayload(UploadBody.Bytes(bytes), attachment.mime, bytes)
        }
        val actualMime = detectImageMimeType(bytes) ?: attachment.mime
        if (actualMime in JPEG_SAFE_IMAGE_MIMES) {
            return UploadPayload(UploadBody.Bytes(bytes), actualMime, bytes)
        }

        val jpeg = runCatching {
            resizeImage(bytes, PHOTO_MAX_LONG_EDGE, PHOTO_JPEG_QUALITY)
        }.getOrNull() ?: return UploadPayload(UploadBody.Bytes(bytes), actualMime, bytes)
        return if (isJpegImage(jpeg)) {
            UploadPayload(UploadBody.Bytes(jpeg), "image/jpeg", jpeg)
        } else {
            UploadPayload(UploadBody.Bytes(bytes), actualMime, bytes)
        }
    }

    private suspend fun readBytes(attachmentId: String): ByteArray? {
        val blob = io { queries.selectBlob(attachmentId).awaitAsOneOrNull() } ?: return null
        blob.data_?.let { return it }
        val path = blob.path ?: return null
        return readLocalFile(path)
    }

    private suspend fun <T> io(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { block() }
}

private data class UploadPayload(
    val body: UploadBody,
    val mime: String,
    /** Present when the payload is already in memory, so poster/thumbnail work can reuse it. */
    val bytes: ByteArray?,
)

internal fun extensionFor(mime: String): String = when {
    mime.startsWith("video/") -> "mp4"
    mime.contains("mp4") || mime.contains("aac") || mime.contains("m4a") -> "m4a"
    mime.contains("webm") -> "webm"
    mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
    mime.contains("png") -> "png"
    mime.contains("webp") -> "webp"
    mime.contains("heic") || mime.contains("heif") -> "heic"
    else -> "bin"
}

private val JPEG_SAFE_IMAGE_MIMES: Set<String> = setOf("image/jpeg", "image/png", "image/webp")
private const val PHOTO_MAX_LONG_EDGE: Int = 4096
private const val PHOTO_JPEG_QUALITY: Int = 90
private const val NON_PORTABLE_VIDEO_REPAIR_KEY: String = "non_portable_video_repair_done"
private const val POSTER_ROTATION_REPAIR_KEY: String = "video_poster_rotation_repair_done"
private const val POSTER_BACKFILL_KEY: String = "video_poster_backfill_at"
private const val POSTER_BACKFILL_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L
private const val PHOTO_THUMBNAIL_MAX_LONG_EDGE: Int = 640
private const val PHOTO_THUMBNAIL_JPEG_QUALITY: Int = 80
private const val THUMBNAIL_BACKFILL_KEY: String = "photo_thumbnail_backfill_at"
private const val THUMBNAIL_BACKFILL_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

/** Reads a captured file from disk. Web has no filesystem, so it stores bytes inline instead. */
internal expect suspend fun readLocalFile(path: String): ByteArray?

/** Gives Android staged media a durable copy independent of the recorder cache. */
internal expect suspend fun preserveLocalFile(path: String): String

internal expect suspend fun localFileExists(path: String): Boolean

/** Removes files owned by Android staging when their blob is deleted or evicted. */
internal expect suspend fun deletePreservedFile(path: String)
