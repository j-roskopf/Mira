package com.joetr.mira.data.archive

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.joetr.mira.data.entries.EntryRepository
import com.joetr.mira.data.entries.media.MediaRepository
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.Entry
import com.joetr.mira.platform.currentTimeMillis
import io.ktor.client.HttpClient
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
    }

    public suspend fun disconnect() {
        write(KEY_TOKEN, "")
        write(KEY_REPO, "")
        _status.value = ArchiveStatus()
    }

    /** Debounced background push. Called after local writes. */
    public fun onLocalWrite() {
        val debounceScope = scope ?: return
        if (!_status.value.connected) return
        debounceJob?.cancel()
        debounceJob = debounceScope.launch {
            delay(PUSH_DEBOUNCE_MS)
            pushNow(ownerIdOrNull() ?: return@launch, includeMedia = false)
        }
    }

    public suspend fun pushNow(ownerId: String, includeMedia: Boolean): ArchiveResult {
        val token = read(KEY_TOKEN) ?: return ArchiveResult.Failed("GitHub is not connected")
        val slug = read(KEY_REPO) ?: return ArchiveResult.Failed("No archive repository")
        val branch = read(KEY_BRANCH) ?: "main"
        val owner = slug.substringBefore('/')
        val repo = slug.substringAfter('/')
        val since = read(KEY_LAST_PUSH)?.toLongOrNull() ?: 0L

        return mutex.withLock {
            _status.value = _status.value.copy(pushing = true, errorMessage = null)

            val changed = entries
                .timeline(ownerId, limit = MAX_PER_PUSH, offset = 0)
                .filter { it.updatedAt > since }

            if (changed.isEmpty()) {
                _status.value = _status.value.copy(pushing = false)
                return@withLock ArchiveResult.NothingToDo
            }

            val mediaFiles = if (includeMedia) collectMedia(changed) else emptyMap()

            val archive = GitHubArchive(httpClient, token)
            val result = archive.push(
                target = ArchiveTarget(owner, repo, branch),
                entries = changed,
                mediaFiles = mediaFiles,
            )

            when (result) {
                is ArchiveResult.Pushed -> {
                    val now = currentTimeMillis()
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
            result
        }
    }

    /**
     * Reads the bytes for every attachment on [changed].
     *
     * Only used when "Include media in backup" is on; the default archive carries links in
     * frontmatter, which keeps pushes small and fast.
     */
    private suspend fun collectMedia(changed: List<Entry>): Map<String, ByteArray> {
        val files = mutableMapOf<String, ByteArray>()
        for (entry in changed) {
            for (attachment in entry.attachments) {
                val bytes = media.bytesFor(attachment) ?: continue
                files[attachment.archiveMediaPath(entry)] = bytes
            }
        }
        return files
    }

    private suspend fun ownerIdOrNull(): String? = read(KEY_OWNER)

    public suspend fun rememberOwner(ownerId: String) {
        write(KEY_OWNER, ownerId)
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
