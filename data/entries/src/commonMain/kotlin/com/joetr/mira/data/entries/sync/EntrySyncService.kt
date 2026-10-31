package com.joetr.mira.data.entries.sync

import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.SyncState
import com.joetr.mira.domain.SyncStatus
import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.platform.currentTimeMillis
import com.joetr.mira.platform.isNetworkAvailable
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
 * Entry rows sync eagerly — text lands on every device within seconds. Media is handled separately
 * by the upload worker, which is why an attachment can reach another device before its bytes do.
 */
public class EntrySyncService(
    database: MiraDatabase,
    private val firebase: MiraFirebase,
    private val scope: CoroutineScope? = null,
) {
    private val backend: EntrySyncBackend = createEntrySyncBackend(database, firebase)
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(SyncState(SyncStatus.SYNCED))
    public val state: StateFlow<SyncState> = _state.asStateFlow()

    private var debounceJob: Job? = null
    private var lastSuccessfulSyncAt: Long = 0L

    public suspend fun syncNow(forcePull: Boolean = false) {
        debounceJob?.cancel()
        runSync(EntrySyncOptions(forcePull = forcePull))
    }

    /** Sync on returning to the foreground, unless a recent run succeeded with nothing queued. */
    public suspend fun syncIfStale() {
        if (!firebase.isConfigured) return
        val hasPending = backend.pendingCount() > 0
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
        _state.value = SyncState(status = SyncStatus.SYNCED, pendingCount = 0)
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
                _state.value = _state.value.copy(status = SyncStatus.SYNCING)
                runCatching {
                    val result = backend.sync(options)
                    if (result.rejected > 0) {
                        error(result.errorMessage ?: "Sync rejected ${result.rejected} change(s)")
                    }
                }.onSuccess {
                    lastSuccessfulSyncAt = currentTimeMillis()
                    refreshState()
                }.onFailure { error ->
                    refreshState(status = SyncStatus.ERROR, errorMessage = error.message)
                }
            }
        }
    }

    private suspend fun refreshState(status: SyncStatus? = null, errorMessage: String? = null) {
        val pending = backend.pendingEntries()
        _state.value = SyncState(
            status = status ?: if (pending.isNotEmpty()) SyncStatus.PENDING else SyncStatus.SYNCED,
            pendingCount = pending.size,
            errorMessage = errorMessage,
            pendingEntries = pending,
        )
    }

    private companion object {
        const val SYNC_DEBOUNCE_MS: Long = 3_000L
        const val FOREGROUND_SYNC_MIN_INTERVAL_MS: Long = 5 * 60 * 1_000L
    }
}
