package com.joetr.mira.data.entries.media

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.joetr.mira.data.entries.EntryRepository
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.AttachmentState
import com.joetr.mira.media.MediaSource
import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.platform.currentTimeMillis
import com.joetr.mira.platform.detectImageMimeType
import com.joetr.mira.platform.isJpegImage
import com.joetr.mira.platform.resizeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val staged = io { queries.selectUnuploadedBlobs().awaitAsList() }
        var uploaded = 0

        for (blob in staged) {
            val entry = entries.entry(blob.entry_id) ?: continue
            val attachment = entry.attachments.firstOrNull { it.id == blob.id } ?: continue
            if (attachment.kind == AttachmentKind.VIDEO && !includeVideo) continue

            val bytes = readBytes(blob.id) ?: continue
            val payload = uploadPayloadFor(attachment, bytes)
            val objectName = firebase.storage.mediaPath(
                ownerId = ownerId,
                entryId = entry.id,
                attachmentId = attachment.id,
                extension = extensionFor(payload.mime),
            )
            val result = runCatching {
                firebase.storage.upload(session.idToken, objectName, payload.bytes, payload.mime)
            }.getOrNull() ?: continue

            val updated = entry.copy(
                attachments = entry.attachments.map {
                    if (it.id != attachment.id) {
                        it
                    } else {
                        it.copy(
                            storagePath = result.storagePath,
                            url = result.downloadUrl,
                            mime = payload.mime,
                            state = AttachmentState.UPLOADED,
                        )
                    }
                },
            )
            entries.save(updated)
            io { queries.markBlobUploaded(blob.id) }
            uploaded++
        }

        if (uploaded > 0) enforceRetention()
        return uploaded
    }

    /**
     * Where a player should read this attachment from.
     *
     * Prefers a local file so playback starts instantly and works offline, falls back to a signed
     * Storage URL so it streams, and only materialises bytes when neither is available — which is
     * the web case, and anything captured but not yet uploaded. Images are always materialised,
     * since the image tile decodes bytes rather than streaming a URL.
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
        // Photos are decoded in memory rather than streamed, so a remote-only image has to be
        // fetched now; handing back a Remote would leave the tile stuck on its placeholder after
        // a reinstall or cache eviction even though the bytes are safely in Storage.
        if (attachment.kind == AttachmentKind.IMAGE) {
            bytesFor(attachment)?.let { return MediaSource.Bytes(it, attachment.mime) }
        }

        attachment.url?.let { return MediaSource.Remote(it) }

        // No local copy and no usable URL: fetch it so playback can still happen.
        val bytes = bytesFor(attachment) ?: return null
        return MediaSource.Bytes(bytes, attachment.mime)
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

    /** Keeps a year permanently local, exempt from eviction. */
    public suspend fun setPinned(attachmentId: String, pinned: Boolean) {
        io { queries.setBlobPinned(if (pinned) 1L else 0L, attachmentId) }
    }

    public suspend fun localBytesUsed(): Long = io {
        queries.totalBlobBytes().awaitAsOneOrNull() ?: 0L
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
        }.flatMap { listOfNotNull(it.storagePath, it.posterPath) }.toSet()

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

    private suspend fun readBytes(attachmentId: String): ByteArray? {
        val blob = io { queries.selectBlob(attachmentId).awaitAsOneOrNull() } ?: return null
        blob.data_?.let { return it }
        val path = blob.path ?: return null
        return readLocalFile(path)
    }

    private suspend fun <T> io(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { block() }
}

private data class UploadPayload(val bytes: ByteArray, val mime: String)

/**
 * Normalises a photo to JPEG before it reaches Storage.
 *
 * Phones hand back HEIC from the camera roll, and HEIC is not viewable in the browser or on every
 * desktop decoder. [resizeImage] uses each platform's own image stack (and heic2any on web) to bake
 * orientation and produce a JPEG every client can show. The local copy keeps its original bytes.
 */
private suspend fun uploadPayloadFor(attachment: Attachment, bytes: ByteArray): UploadPayload {
    if (attachment.kind != AttachmentKind.IMAGE) return UploadPayload(bytes, attachment.mime)
    val actualMime = detectImageMimeType(bytes) ?: attachment.mime
    if (actualMime in JPEG_SAFE_IMAGE_MIMES) return UploadPayload(bytes, actualMime)

    val jpeg = runCatching {
        resizeImage(bytes, PHOTO_MAX_LONG_EDGE, PHOTO_JPEG_QUALITY)
    }.getOrNull() ?: return UploadPayload(bytes, actualMime)
    return if (isJpegImage(jpeg)) UploadPayload(jpeg, "image/jpeg") else UploadPayload(bytes, actualMime)
}

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

/** Reads a captured file from disk. Web has no filesystem, so it stores bytes inline instead. */
internal expect suspend fun readLocalFile(path: String): ByteArray?

/** Gives Android staged media a durable copy independent of the recorder cache. */
internal expect suspend fun preserveLocalFile(path: String): String

internal expect suspend fun localFileExists(path: String): Boolean

/** Removes files owned by Android staging when their blob is deleted or evicted. */
internal expect suspend fun deletePreservedFile(path: String)
