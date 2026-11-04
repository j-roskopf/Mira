package com.joetr.mira.data.archive

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.joetr.mira.data.entries.EntryRepository
import com.joetr.mira.data.entries.media.MediaRepository
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.Entry
import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.network.RemoteArchiveConfig
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

public data class ArchiveStatus(
    val connected: Boolean = false,
    val repoSlug: String? = null,
    val lastPushedAt: Long? = null,
    val pushing: Boolean = false,
    val errorMessage: String? = null,
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
) {
    private val queries get() = database.entriesQueries
    private val mutex = Mutex()
    private val _status = MutableStateFlow(ArchiveStatus())
    public val status: StateFlow<ArchiveStatus> = _status.asStateFlow()

    private var debounceJob: Job? = null

    public suspend fun load() {
        val token = read(KEY_TOKEN)
        val slug = read(KEY_REPO)
        _status.value = ArchiveStatus(
            connected = token != null && slug != null,
            repoSlug = slug,
            lastPushedAt = read(KEY_LAST_PUSH)?.toLongOrNull(),
        )
    }

    public suspend fun connect(token: String, target: ArchiveTarget) {
        write(KEY_TOKEN, token)
        write(KEY_REPO, target.slug)
        write(KEY_BRANCH, target.branch)
        _status.value = _status.value.copy(connected = true, repoSlug = target.slug)
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
        write(KEY_TOKEN, token)
        _status.value = _status.value.copy(
            connected = true,
            repoSlug = slug,
            errorMessage = null,
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
    public suspend fun refreshSharedConfig() {
        val firebase = firebase ?: return
        if (!isNetworkAvailable()) return
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
            mutex.withLock {
                write(KEY_TOKEN, "")
                write(KEY_REPO, "")
                write(KEY_BRANCH, "")
                write(KEY_LAST_PUSH, "")
                _status.value = ArchiveStatus()
            }
            return
        }
        val slug = remote.repoSlug ?: return
        mutex.withLock {
            write(KEY_OWNER, session.localId)
            write(KEY_REPO, slug)
            write(KEY_BRANCH, remote.branch)
            _status.value = _status.value.copy(
                connected = read(KEY_TOKEN) != null,
                repoSlug = slug,
                errorMessage = null,
            )
        }
    }

    /** Stops backing up from this device, leaving the account-wide target in place. */
    public suspend fun disconnect() {
        debounceJob?.cancel()
        debounceJob = null
        mutex.withLock {
            write(KEY_TOKEN, "")
            write(KEY_LAST_PUSH, "")
            _status.value = _status.value.copy(
                connected = false,
                pushing = false,
                errorMessage = null,
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
            pushNow(ownerIdOrNull() ?: return@launch, includeMedia = read("archive_include_media") == "true")
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

            _status.value = _status.value.copy(pushing = true, errorMessage = null)

            val mediaKeys = mutableListOf<String>()
            val noteVersions = mutableMapOf<String, String>()
            val unavailableMedia = mutableListOf<String>()
            var changed = emptyList<Entry>()
            val result = try {
                val all = mutableListOf<Entry>()
                var offset = 0
                do {
                    val page = entries.timeline(ownerId, limit = MAX_PER_PUSH, offset = offset)
                    all.addAll(page)
                    offset += page.size
                } while (page.size == MAX_PER_PUSH)
                val mediaFiles = mutableMapOf<String, ByteArray>()
                val archivedMediaPaths = mutableSetOf<String>()
                val notesToPush = mutableListOf<Entry>()
                for (entry in all) {
                    for (attachment in entry.attachments) {
                        val path = attachment.archiveMediaPath(entry)
                        val key = "github_media:$ownerId:$slug:$branch:$path:${attachment.bytes}"
                        if (read(key) == "true") {
                            archivedMediaPaths.add(path)
                            continue
                        }
                        if (!includeMedia) continue
                        val bytes = media.bytesFor(attachment)
                        if (bytes == null || bytes.isEmpty()) {
                            unavailableMedia.add("${entry.localDate.value} (${attachment.id})")
                            continue
                        }
                        mediaFiles[path] = bytes
                        archivedMediaPaths.add(path)
                        mediaKeys.add(key)
                    }
                    // Upgrade existing notes once, and rewrite them when media becomes available.
                    val noteKey = "github_note_links:$ownerId:$slug:$branch:${entry.archivePath()}"
                    val version = "1:" + entry.attachments.map { it.archiveMediaPath(entry) }
                        .filter { it in archivedMediaPaths }.sorted().joinToString(",")
                    if (entry.updatedAt > since ||
                        (entry.attachments.isNotEmpty() && read(noteKey) != version)
                    ) {
                        notesToPush.add(entry)
                        noteVersions[noteKey] = version
                    }
                }
                changed = notesToPush
                GitHubArchive(httpClient, token).push(
                    target = ArchiveTarget(owner, repo, branch),
                    entries = changed,
                    mediaFiles = mediaFiles,
                    archivedMediaPaths = archivedMediaPaths,
                )
            } catch (cancelled: CancellationException) {
                _status.value = _status.value.copy(pushing = false)
                throw cancelled
            } catch (error: Exception) {
                ArchiveResult.Failed(error.message ?: "Backup failed")
            }

            when (result) {
                is ArchiveResult.Pushed -> {
                    // A note edited during the request must remain eligible for the next push.
                    val now = changed.maxOfOrNull { it.updatedAt } ?: since
                    mediaKeys.forEach { write(it, "true") }
                    noteVersions.forEach { (key, version) -> write(key, version) }
                    write(KEY_LAST_PUSH, now.toString())
                    _status.value = _status.value.copy(
                        pushing = false,
                        lastPushedAt = now,
                        errorMessage = null,
                    )
                }
                is ArchiveResult.Failed ->
                    _status.value = _status.value.copy(
                        pushing = false,
                        errorMessage = result.message,
                    )
                ArchiveResult.NothingToDo ->
                    _status.value = _status.value.copy(pushing = false)
            }
            if (unavailableMedia.isNotEmpty() && result !is ArchiveResult.Failed) {
                val message = "Backup incomplete. Could not read ${unavailableMedia.size} recording(s), " +
                    "including ${unavailableMedia.first()}. The local file may be missing and no cloud copy " +
                    "could be loaded. Available files were backed up; missing recordings will be retried."
                _status.value = _status.value.copy(errorMessage = message)
                ArchiveResult.Failed(message)
            } else {
                result
            }
        }
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
        const val KEY_OWNER = "github_archive_owner_id"

        const val PUSH_DEBOUNCE_MS: Long = 5 * 60 * 1000
        const val MAX_PER_PUSH = 500
    }
}
