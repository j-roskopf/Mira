package com.joetr.mira.data.archive

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.joetr.mira.data.entries.EntryRepository
import com.joetr.mira.data.entries.media.ArchiveMediaSource
import com.joetr.mira.data.entries.media.MediaRepository
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.GITHUB_ARCHIVE_MAX_MEDIA_BYTES
import com.joetr.mira.domain.GITHUB_ARCHIVE_MAX_SOURCE_BYTES
import com.joetr.mira.domain.GITHUB_ARCHIVE_PART_MAX_BYTES
import com.joetr.mira.domain.GITHUB_ARCHIVE_TARGET_MEDIA_BYTES
import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.network.RemoteArchiveConfig
import com.joetr.mira.platform.currentTimeMillis
import com.joetr.mira.platform.isNetworkAvailable
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the archive is doing right now, so a long push is not an opaque spinner. */
public enum class ArchivePhase {
    PREPARING,
    COMPRESSING,
    UPLOADING,
    COMMITTING,
}

/**
 * Live progress for one push.
 *
 * [completed] counts attachments reached so far; [label] names the current one. The timestamp lets
 * the UI show elapsed time while a large recording is being re-encoded.
 */
public data class ArchiveProgress(
    val phase: ArchivePhase,
    val completed: Int,
    val total: Int,
    val label: String? = null,
    val startedAt: Long,
)

public data class ArchiveStatus(
    val connected: Boolean = false,
    val repoSlug: String? = null,
    val lastPushedAt: Long? = null,
    val pushing: Boolean = false,
    val errorMessage: String? = null,
    val progress: ArchiveProgress? = null,
)

/**
 * Keeps the GitHub archive current.
 *
 * Pushes are debounced ~5 minutes after the last change, triggered manually from Settings, and run
 * on close on desktop. Entries changed since the last successful push are sent as one commit.
 */
public class ArchiveService(
    private val database: MiraDatabase,
    private val entries: EntryRepository,
    private val media: MediaRepository,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope? = null,
    private val firebase: MiraFirebase? = null,
    private val targetMediaBytes: Long = GITHUB_ARCHIVE_TARGET_MEDIA_BYTES,
    private val hardLimitBytes: Long = GITHUB_ARCHIVE_MAX_MEDIA_BYTES,
    private val partLimitBytes: Long = GITHUB_ARCHIVE_PART_MAX_BYTES,
    private val maxSourceBytes: Long = GITHUB_ARCHIVE_MAX_SOURCE_BYTES,
) {
    private val queries get() = database.entriesQueries
    private val mutex = Mutex()
    private val _status = MutableStateFlow(ArchiveStatus())
    public val status: StateFlow<ArchiveStatus> = _status.asStateFlow()

    private var debounceJob: Job? = null
    private var retryJob: Job? = null
    private var lastSharedConfigRefreshAt: Long = 0L

    public suspend fun load() {
        val token = read(KEY_TOKEN)
        val slug = read(KEY_REPO)
        _status.value = ArchiveStatus(
            connected = token != null && slug != null,
            repoSlug = slug,
            lastPushedAt = read(KEY_LAST_PUSH)?.toLongOrNull(),
            errorMessage = read(KEY_LAST_ERROR),
        )
    }

    public suspend fun connect(token: String, target: ArchiveTarget) {
        retryJob?.cancel()
        retryJob = null
        write(KEY_TOKEN, token)
        write(KEY_REPO, target.slug)
        write(KEY_BRANCH, target.branch)
        write(KEY_LAST_ERROR, "")
        write(KEY_PENDING, "")
        _status.value = _status.value.copy(
            connected = true,
            repoSlug = target.slug,
            errorMessage = null,
            progress = null,
        )
        publishTarget(target)
    }

    /**
     * Authorizes this device for the target another device already published.
     *
     * Only the token is device-local; the repository and branch came from the shared config, so
     * there is no picker here. Returns false when no target is known yet.
     */
    public suspend fun authorize(token: String): Boolean {
        val slug = read(KEY_REPO) ?: return false
        val branch = read(KEY_BRANCH) ?: "main"
        retryJob?.cancel()
        retryJob = null
        write(KEY_TOKEN, token)
        write(KEY_LAST_ERROR, "")
        write(KEY_PENDING, "")
        _status.value = _status.value.copy(
            connected = true,
            repoSlug = slug,
            errorMessage = null,
            progress = null,
        )
        publishTarget(ArchiveTarget(slug.substringBefore('/'), slug.substringAfter('/'), branch))
        return true
    }

    /**
     * Applies the account-wide archive target from Firestore.
     *
     * The token is never synced, so a device that has not authorized yet ends up with a target but
     * no [ArchiveStatus.connected]. A missing document means this account predates the shared
     * config (or connected while offline), so the local target is published instead of erased.
     */
    /** Same as [refreshSharedConfig], but skips the Firestore read if one ran recently. */
    public suspend fun refreshSharedConfigIfStale() {
        if (currentTimeMillis() - lastSharedConfigRefreshAt < SHARED_CONFIG_MIN_INTERVAL_MS) return
        refreshSharedConfig()
    }

    public suspend fun refreshSharedConfig() {
        val firebase = firebase ?: return
        if (!isNetworkAvailable()) return
        lastSharedConfigRefreshAt = currentTimeMillis()
        val session = try {
            firebase.currentSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        } ?: return
        val remote = try {
            firebase.firestore.readArchiveConfig(session.idToken, session.localId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        if (remote == null) {
            val slug = read(KEY_REPO) ?: return
            val branch = read(KEY_BRANCH) ?: "main"
            write(KEY_OWNER, session.localId)
            try {
                firebase.firestore.writeArchiveConfig(
                    idToken = session.idToken,
                    ownerId = session.localId,
                    config = RemoteArchiveConfig(enabled = true, repoSlug = slug, branch = branch),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Offline or rate-limited; the local target still works.
            }
            return
        }
        if (!remote.enabled) {
            retryJob?.cancel()
            retryJob = null
            mutex.withLock {
                write(KEY_TOKEN, "")
                write(KEY_REPO, "")
                write(KEY_BRANCH, "")
                write(KEY_LAST_PUSH, "")
                write(KEY_LAST_ERROR, "")
                write(KEY_PENDING, "")
                _status.value = ArchiveStatus()
            }
            return
        }
        val slug = remote.repoSlug ?: return
        mutex.withLock {
            write(KEY_OWNER, session.localId)
            write(KEY_REPO, slug)
            write(KEY_BRANCH, remote.branch)
            // Do not clear an existing failure here: a config refresh says nothing about whether
            // the last backup reached GitHub.
            _status.value = _status.value.copy(
                connected = read(KEY_TOKEN) != null,
                repoSlug = slug,
            )
        }
    }

    /** Stops backing up from this device, leaving the account-wide target in place. */
    public suspend fun disconnect() {
        debounceJob?.cancel()
        debounceJob = null
        retryJob?.cancel()
        retryJob = null
        mutex.withLock {
            write(KEY_TOKEN, "")
            write(KEY_LAST_PUSH, "")
            write(KEY_LAST_ERROR, "")
            write(KEY_PENDING, "")
            _status.value = _status.value.copy(
                connected = false,
                pushing = false,
                errorMessage = null,
                progress = null,
            )
        }
    }

    /**
     * Removes the archive target for the whole account.
     *
     * The remote flag is written first; if that fails the local connection is left intact so a
     * transient network error cannot silently resurrect the archive on the next refresh.
     */
    public suspend fun removeArchiveTarget() {
        debounceJob?.cancel()
        debounceJob = null
        retryJob?.cancel()
        retryJob = null
        val firebase = firebase
        val session = if (firebase != null) {
            try {
                firebase.currentSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (firebase != null && session != null) {
            try {
                firebase.firestore.writeArchiveConfig(
                    idToken = session.idToken,
                    ownerId = session.localId,
                    config = RemoteArchiveConfig(enabled = false, repoSlug = null),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _status.value = _status.value.copy(
                    errorMessage = "Could not remove the archive target. Check your connection and try again.",
                )
                return
            }
        }
        mutex.withLock {
            write(KEY_TOKEN, "")
            write(KEY_REPO, "")
            write(KEY_BRANCH, "")
            write(KEY_LAST_PUSH, "")
            write(KEY_LAST_ERROR, "")
            write(KEY_PENDING, "")
            _status.value = ArchiveStatus()
        }
    }

    /** Debounced background push. Called after local writes. */
    public fun onLocalWrite() {
        val debounceScope = scope ?: return
        if (!_status.value.connected) return
        debounceJob?.cancel()
        debounceJob = debounceScope.launch {
            delay(PUSH_DEBOUNCE_MS)
            pushNow(ownerIdOrNull() ?: return@launch, includeMedia = read(KEY_INCLUDE_MEDIA) == "true")
        }
    }

    /**
     * Retries a failed push without waiting for the next local write.
     *
     * Called on app foreground and by the scheduled retry after a failure. A successful push
     * clears the error, which also dismisses the persistent backup-failure indicator.
     */
    public suspend fun retryIfNeeded() {
        if (!_status.value.connected) return
        if (_status.value.pushing) return
        // A push interrupted by process death leaves this flag behind; without it, returning to
        // the app would silently stop short of the last successful commit.
        val pending = read(KEY_PENDING) == "true"
        if (_status.value.errorMessage == null && !pending) return
        if (!isNetworkAvailable()) return
        val ownerId = ownerIdOrNull() ?: return
        pushNow(ownerId, includeMedia = read(KEY_INCLUDE_MEDIA) == "true")
    }

    private fun scheduleRetry() {
        val retryScope = scope ?: return
        if (!_status.value.connected) return
        retryJob?.cancel()
        retryJob = retryScope.launch {
            delay(RETRY_DELAY_MS)
            // Clear first so a success inside the retry does not cancel this coroutine itself.
            retryJob = null
            retryIfNeeded()
        }
    }

    public suspend fun pushNow(ownerId: String, includeMedia: Boolean): ArchiveResult {
        return mutex.withLock {
            val token = read(KEY_TOKEN) ?: return@withLock ArchiveResult.Failed("GitHub is not connected")
            val slug = read(KEY_REPO) ?: return@withLock ArchiveResult.Failed("No archive repository")
            val branch = read(KEY_BRANCH) ?: "main"
            val owner = slug.substringBefore('/')
            val repo = slug.substringAfter('/')
            val since = read(KEY_LAST_PUSH)?.toLongOrNull() ?: 0L
            write(KEY_PENDING, "true")
            val startedAt = currentTimeMillis()

            _status.value = _status.value.copy(
                pushing = true,
                errorMessage = null,
                progress = ArchiveProgress(ArchivePhase.PREPARING, 0, 0, null, startedAt),
            )

            val mediaKeys = mutableMapOf<String, String>()
            val noteVersions = mutableMapOf<String, String>()
            val unavailableMedia = mutableListOf<String>()
            val skippedMedia = mutableListOf<String>()
            val fitCleanups = mutableListOf<() -> Unit>()
            val temporaryFiles = mutableListOf<String>()
            var changed = emptyList<Entry>()
            val result = try {
                val all = mutableListOf<Entry>()
                var offset = 0
                do {
                    val page = entries.timeline(ownerId, limit = MAX_PER_PUSH, offset = offset)
                    all.addAll(page)
                    offset += page.size
                } while (page.size == MAX_PER_PUSH)
                val mediaFiles = mutableMapOf<String, ArchiveMedia>()
                val archivedMediaPaths = mutableSetOf<String>()
                val archivedMedia = mutableMapOf<String, ArchivedMedia>()
                val notesToPush = mutableListOf<Entry>()
                val archiveTarget = ArchiveTarget(owner, repo, branch)
                val total = all.sumOf { it.attachments.size }
                var completed = 0

                fun report(phase: ArchivePhase, label: String? = null) {
                    _status.value = _status.value.copy(
                        progress = ArchiveProgress(phase, completed, total, label, startedAt),
                    )
                }

                fun describe(entry: Entry, attachment: Attachment): String {
                    val megabytes = attachment.bytes / (1024 * 1024)
                    return if (megabytes > 0) {
                        "${entry.localDate.value} · $megabytes MB"
                    } else {
                        entry.localDate.value
                    }
                }

                fun cacheKey(path: String, attachment: Attachment): String =
                    "github_media:v3:$ownerId:$slug:$branch:$path:${attachment.bytes}"

                fun addSingle(
                    attachment: Attachment,
                    path: String,
                    key: String,
                    media: ArchiveMedia,
                ) {
                    mediaFiles[path] = media
                    archivedMedia[attachment.id] = ArchivedMedia(path)
                    archivedMediaPaths.add(path)
                    mediaKeys[key] = "v2:single"
                }

                fun addSplit(
                    entry: Entry,
                    attachment: Attachment,
                    key: String,
                    parts: List<com.joetr.mira.media.ArchiveFitPart>,
                ) {
                    val total = parts.size
                    val paths = parts.mapIndexed { index, part ->
                        val partPath = attachment.archivePartPath(entry, index + 1, total)
                        mediaFiles[partPath] = part.path?.let { ArchiveMedia.LocalFile(it) }
                            ?: ArchiveMedia.Bytes(part.bytes ?: ByteArray(0))
                        partPath
                    }
                    archivedMedia[attachment.id] = ArchivedMedia(paths)
                    archivedMediaPaths.addAll(paths)
                    mediaKeys[key] = "v2:parts:$total"
                }

                suspend fun archiveOne(
                    entry: Entry,
                    attachment: Attachment,
                    source: ArchiveMediaSource,
                ) {
                    if (source is ArchiveMediaSource.LocalFile && source.temporary) {
                        temporaryFiles += source.path
                    }
                    val path = attachment.archiveMediaPath(entry)
                    val key = cacheKey(path, attachment)
                    val size = when (source) {
                        is ArchiveMediaSource.LocalFile -> source.bytes
                        is ArchiveMediaSource.Bytes -> source.bytes.size.toLong()
                        is ArchiveMediaSource.TooLarge -> return
                    }
                    if (size <= targetMediaBytes) {
                        val media = when (source) {
                            is ArchiveMediaSource.LocalFile -> ArchiveMedia.LocalFile(source.path)
                            is ArchiveMediaSource.Bytes -> ArchiveMedia.Bytes(source.bytes)
                            is ArchiveMediaSource.TooLarge -> return
                        }
                        addSingle(attachment, path, key, media)
                        return
                    }
                    val fit = com.joetr.mira.media.fitArchiveMedia(
                        com.joetr.mira.media.ArchiveFitRequest(
                            path = (source as? ArchiveMediaSource.LocalFile)?.path,
                            bytes = (source as? ArchiveMediaSource.Bytes)?.bytes,
                            mime = attachment.mime,
                            durationMs = attachment.durationMs,
                            targetBytes = targetMediaBytes,
                            hardLimitBytes = hardLimitBytes,
                            partLimitBytes = partLimitBytes,
                        ),
                    )
                    when (fit) {
                        is com.joetr.mira.media.ArchiveFitResult.Whole -> {
                            fitCleanups += { fit.cleanup() }
                            val media = fit.path?.let { ArchiveMedia.LocalFile(it) }
                                ?: ArchiveMedia.Bytes(fit.bytes ?: ByteArray(0))
                            addSingle(attachment, path, key, media)
                        }
                        is com.joetr.mira.media.ArchiveFitResult.Split -> {
                            fitCleanups += { fit.cleanup() }
                            addSplit(entry, attachment, key, fit.parts)
                        }
                        com.joetr.mira.media.ArchiveFitResult.Unsupported,
                        com.joetr.mira.media.ArchiveFitResult.Failed,
                        -> {
                            if (size <= hardLimitBytes) {
                                val media = when (source) {
                                    is ArchiveMediaSource.LocalFile ->
                                        ArchiveMedia.LocalFile(source.path)
                                    is ArchiveMediaSource.Bytes ->
                                        ArchiveMedia.Bytes(source.bytes)
                                    is ArchiveMediaSource.TooLarge -> return
                                }
                                addSingle(attachment, path, key, media)
                            } else {
                                skippedMedia.add(
                                    "${entry.localDate.value} (${size / (1024 * 1024)} MB)",
                                )
                            }
                        }
                    }
                }

                report(ArchivePhase.PREPARING)
                for (entry in all) {
                    for (attachment in entry.attachments) {
                        completed += 1
                        val path = attachment.archiveMediaPath(entry)
                        val key = cacheKey(path, attachment)
                        // Older archives cached a bare "true"; accept it so upgrading does not
                        // re-upload every recording that was already backed up.
                        val cached = read(key) ?: run {
                            val legacy = "github_media:$ownerId:$slug:$branch:$path:${attachment.bytes}"
                            if (read(legacy) == "true") "v2:single" else null
                        }
                        if (cached != null) {
                            cachedArchivedMedia(cached, entry, attachment)?.let { archived ->
                                archivedMedia[attachment.id] = archived
                                archivedMediaPaths.addAll(archived.paths)
                            }
                            continue
                        }
                        if (!includeMedia) continue
                        report(
                            if (attachment.bytes > targetMediaBytes) {
                                ArchivePhase.COMPRESSING
                            } else {
                                ArchivePhase.UPLOADING
                            },
                            describe(entry, attachment),
                        )
                        val source = media.archiveMediaSourceFor(attachment, maxSourceBytes)
                        when (source) {
                            null -> {
                                unavailableMedia.add("${entry.localDate.value} (${attachment.id})")
                                continue
                            }
                            is ArchiveMediaSource.TooLarge -> {
                                skippedMedia.add(
                                    "${entry.localDate.value} (${source.bytes / (1024 * 1024)} MB)",
                                )
                                continue
                            }
                            is ArchiveMediaSource.Bytes -> {
                                if (source.bytes.isEmpty()) {
                                    unavailableMedia.add("${entry.localDate.value} (${attachment.id})")
                                    continue
                                }
                                archiveOne(entry, attachment, source)
                            }
                            is ArchiveMediaSource.LocalFile -> archiveOne(entry, attachment, source)
                        }
                    }
                    // Upgrade existing notes once, and rewrite them when media becomes available.
                    val noteKey = "github_note_links:$ownerId:$slug:$branch:${entry.archivePath()}"
                    val version = "2:" + entry.attachments
                        .flatMap { archivedMedia[it.id]?.paths ?: emptyList() }
                        .sorted()
                        .joinToString(",")
                    if (entry.updatedAt > since ||
                        (entry.attachments.isNotEmpty() && read(noteKey) != version)
                    ) {
                        notesToPush.add(entry)
                        noteVersions[noteKey] = version
                    }
                }
                changed = notesToPush
                completed = total
                report(ArchivePhase.COMMITTING)
                GitHubArchive(httpClient, token).push(
                    target = archiveTarget,
                    entries = changed,
                    mediaFiles = mediaFiles,
                    archivedMedia = archivedMedia,
                )
            } catch (cancelled: CancellationException) {
                fitCleanups.forEach { cleanup -> runCatching { cleanup() } }
                temporaryFiles.forEach { path -> media.deleteArchiveTemp(path) }
                _status.value = _status.value.copy(pushing = false, progress = null)
                throw cancelled
            } catch (error: Exception) {
                ArchiveResult.Failed(error.message ?: "Backup failed")
            }
            fitCleanups.forEach { cleanup -> runCatching { cleanup() } }
            temporaryFiles.forEach { path -> media.deleteArchiveTemp(path) }

            when (result) {
                is ArchiveResult.Pushed -> {
                    // A note edited during the request must remain eligible for the next push.
                    val now = changed.maxOfOrNull { it.updatedAt } ?: since
                    mediaKeys.forEach { (key, value) -> write(key, value) }
                    noteVersions.forEach { (key, version) -> write(key, version) }
                    write(KEY_LAST_PUSH, now.toString())
                    write(KEY_LAST_ERROR, "")
                    write(KEY_PENDING, "")
                    retryJob?.cancel()
                    retryJob = null
                    _status.value = _status.value.copy(
                        pushing = false,
                        lastPushedAt = now,
                        errorMessage = null,
                        progress = null,
                    )
                }
                is ArchiveResult.Failed -> {
                    write(KEY_LAST_ERROR, result.message)
                    _status.value = _status.value.copy(
                        pushing = false,
                        errorMessage = result.message,
                        progress = null,
                    )
                    scheduleRetry()
                }
                ArchiveResult.NothingToDo -> {
                    write(KEY_PENDING, "")
                    _status.value = _status.value.copy(pushing = false, progress = null)
                }
            }
            val problems = buildList {
                if (unavailableMedia.isNotEmpty()) {
                    add(
                        "Could not read ${unavailableMedia.size} recording(s), including " +
                            "${unavailableMedia.first()}. The local file may be missing and no cloud " +
                            "copy could be loaded.",
                    )
                }
                if (skippedMedia.isNotEmpty()) {
                    add(
                        "${skippedMedia.size} recording(s) are too large for the archive " +
                            "(GitHub's per-blob payload limit cannot be reduced, or the source is " +
                            "above the archive's size ceiling) and were not backed up, including " +
                            "${skippedMedia.first()}. They remain available in cloud storage.",
                    )
                }
            }
            if (problems.isNotEmpty() && result !is ArchiveResult.Failed) {
                val message = "Backup incomplete. ${problems.joinToString(" ")} " +
                    "Available files were backed up; skipped recordings will be retried."
                write(KEY_LAST_ERROR, message)
                _status.value = _status.value.copy(errorMessage = message)
                scheduleRetry()
                ArchiveResult.Failed(message)
            } else {
                result
            }
        }
    }

    private fun cachedArchivedMedia(
        value: String,
        entry: Entry,
        attachment: Attachment,
    ): ArchivedMedia? = when {
        value == "true" || value == "v2:single" ->
            ArchivedMedia(attachment.archiveMediaPath(entry))
        value.startsWith("v2:parts:") ->
            value.removePrefix("v2:parts:").toIntOrNull()?.let { count ->
                ArchivedMedia((1..count).map { attachment.archivePartPath(entry, it, count) })
            }
        else -> null
    }

    private suspend fun ownerIdOrNull(): String? = read(KEY_OWNER)

    public suspend fun rememberOwner(ownerId: String) {
        write(KEY_OWNER, ownerId)
    }

    /** Best-effort publish of the target; a failure never blocks the local connection. */
    private suspend fun publishTarget(target: ArchiveTarget) {
        val firebase = firebase ?: return
        val session = try {
            firebase.currentSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        } ?: return
        write(KEY_OWNER, session.localId)
        try {
            firebase.firestore.writeArchiveConfig(
                idToken = session.idToken,
                ownerId = session.localId,
                config = RemoteArchiveConfig(
                    enabled = true,
                    repoSlug = target.slug,
                    branch = target.branch,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Offline, signed out, or rate-limited. The next refresh will publish it.
        }
    }

    private suspend fun read(key: String): String? = withContext(Dispatchers.Default) {
        queries.getSetting(key).awaitAsOneOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun write(key: String, value: String) {
        withContext(Dispatchers.Default) { queries.putSetting(key, value) }
    }

    private companion object {
        const val KEY_TOKEN = "github_archive_token"
        const val KEY_REPO = "github_archive_repo"
        const val KEY_BRANCH = "github_archive_branch"
        const val KEY_LAST_PUSH = "github_archive_last_push"
        const val KEY_LAST_ERROR = "github_archive_last_error"
        const val KEY_OWNER = "github_archive_owner_id"
        const val KEY_INCLUDE_MEDIA = "archive_include_media"
        const val KEY_PENDING = "github_archive_pending"

        const val PUSH_DEBOUNCE_MS: Long = 5 * 60 * 1000
        const val RETRY_DELAY_MS: Long = 15 * 60 * 1000
        const val MAX_PER_PUSH = 500

        /**
         * Shared config rarely changes, but [refreshSharedConfig] is wired to every Activity
         * resume — including returning from the camera, a media picker, or a permission dialog —
         * not just genuine app-foreground events. Without this gate, a session of testing media
         * capture burns one Firestore read per round-trip.
         */
        const val SHARED_CONFIG_MIN_INTERVAL_MS: Long = 15 * 60 * 1000
    }
}
