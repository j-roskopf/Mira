package com.joetr.mira.data.entries.sync

import com.joetr.mira.data.entries.media.MediaRepository
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.SyncState
import com.joetr.mira.domain.SyncStatus
import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.platform.currentTimeMillis
import com.joetr.mira.platform.isNetworkAvailable
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

/**
 * Drains the entry outbox to Firestore and merges remote changes back.
 *
 * Entry rows sync eagerly — text lands on every device within seconds. Attachment bytes are
 * uploaded first and the owning rows pushed afterwards, so a remote device never sees an entry
 * referencing media the cloud does not have. A run is only [SyncStatus.SYNCED] once both are done.
 */
public class EntrySyncService(
    database: MiraDatabase,
    private val firebase: MiraFirebase,
    private val media: MediaRepository,
    private val scope: CoroutineScope? = null,
    private val includeVideo: suspend () -> Boolean = { true },
) {
    private val backend: EntrySyncBackend = createEntrySyncBackend(database, firebase)
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(SyncState(SyncStatus.SYNCED))
    public val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Bumped after each successful sync so observers can reload data the sync may have changed. */
    private val _revision = MutableStateFlow(0L)
    public val revision: StateFlow<Long> = _revision.asStateFlow()

    private var debounceJob: Job? = null
    private var lastSuccessfulSyncAt: Long = 0L

    public suspend fun syncNow(forcePull: Boolean = false) {
        debounceJob?.cancel()
        runSync(EntrySyncOptions(forcePull = forcePull))
    }

    /** Sync on returning to the foreground, unless a recent run succeeded with nothing queued. */
    public suspend fun syncIfStale() {
        if (!firebase.isConfigured) return
        val hasPending = backend.pendingCount() > 0 || media.pendingUploadCount() > 0
        if (
            !hasPending &&
            lastSuccessfulSyncAt > 0L &&
            currentTimeMillis() - lastSuccessfulSyncAt < FOREGROUND_SYNC_MIN_INTERVAL_MS
        ) {
            return
        }
        syncNow()
    }

    public suspend fun retryFailed() {
        backend.resetPullCursor()
        syncNow(forcePull = true)
    }

    /** Full pull after sign-in, resetting the pagination cursor first. */
    public suspend fun syncAfterSignIn() {
        debounceJob?.cancel()
        backend.resetPullCursor()
        syncNow(forcePull = true)
    }

    public suspend fun dropPendingSync() {
        backend.dropPending()
        refreshState()
    }

    public suspend fun dropPendingSyncEntry(id: String) {
        backend.dropPendingEntry(id)
        refreshState()
    }

    /** Called after any local write; coalesces a burst of edits into one run. */
    public fun onLocalWrite() {
        val debounceScope = scope ?: return
        debounceJob?.cancel()
        debounceJob = debounceScope.launch {
            delay(SYNC_DEBOUNCE_MS)
            runSync(EntrySyncOptions())
        }
    }

    private suspend fun runSync(options: EntrySyncOptions) {
        if (!firebase.isConfigured) return
        if (!isNetworkAvailable()) {
            refreshState()
            return
        }
        syncMutex.withLock {
            withContext(Dispatchers.Default) {
                if (firebase.currentIdToken().isNullOrBlank()) {
                    refreshState(status = SyncStatus.ERROR, errorMessage = "Not signed in to sync")
                    return@withContext
                }
                // Refresh first so the UI can name the attachments about to upload, not just say
                // "Syncing" from a stale count.
                refreshState()
                _state.value = _state.value.copy(status = SyncStatus.SYNCING)
                // Attachment bytes are best-effort: a failed upload must never stop the entry
                // itself from reaching the cloud. Anything left behind stays queued for the next
                // run, and refreshState reports it as pending rather than synced.
                runCatchingNonCancellation { uploadPendingMedia() }
                runCatchingNonCancellation {
                    val result = backend.sync(options)
                    if (result.rejected > 0) {
                        error(result.errorMessage ?: "Sync rejected ${result.rejected} change(s)")
                    }
                }.onSuccess {
                    lastSuccessfulSyncAt = currentTimeMillis()
                    _revision.value++
                    refreshState()
                }.onFailure { error ->
                    refreshState(status = SyncStatus.ERROR, errorMessage = error.message)
                }
            }
        }
    }

    /**
     * Uploads attachment bytes before pushing entries, so another device never receives an entry
     * that points at media the cloud does not have yet.
     */
    private suspend fun uploadPendingMedia() {
        val ownerId = firebase.sessionForSync()?.localId ?: return
        media.uploadPending(ownerId, includeVideo = includeVideo())
    }

    private suspend fun refreshState(status: SyncStatus? = null, errorMessage: String? = null) {
        val pending = backend.pendingEntries()
        val pendingMedia = media.pendingUploadCount()
        _state.value = SyncState(
            status = status ?: if (pending.isNotEmpty() || pendingMedia > 0) {
                SyncStatus.PENDING
            } else {
                SyncStatus.SYNCED
            },
            pendingCount = pending.size,
            pendingMediaCount = pendingMedia,
            errorMessage = errorMessage,
            pendingEntries = pending,
        )
    }

    private companion object {
        const val SYNC_DEBOUNCE_MS: Long = 3_000L
        const val FOREGROUND_SYNC_MIN_INTERVAL_MS: Long = 5 * 60 * 1_000L
    }
}

/** Like [runCatching], but never turns cancellation into an ordinary failure. */
private inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
