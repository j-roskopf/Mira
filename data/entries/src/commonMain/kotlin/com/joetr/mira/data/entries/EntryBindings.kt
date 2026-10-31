package com.joetr.mira.data.entries

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.joetr.mira.data.entries.sync.EntrySyncService
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.EntryStore
import com.joetr.mira.domain.OutboxKind
import com.joetr.mira.domain.SyncCoordinator
import com.joetr.mira.domain.SyncState
import com.joetr.mira.platform.currentTimeMillis
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
public class DefaultEntryStore(
    private val database: MiraDatabase,
) : EntryStore {

    private val queries get() = database.entriesQueries

    override suspend fun countByOwner(ownerId: String): Int = withContext(Dispatchers.Default) {
        queries.countEntriesByOwner(ownerId).awaitAsOneOrNull()?.toInt() ?: 0
    }

    /**
     * Re-owns every entry written before sign-in, and queues them all for push.
     *
     * This is what makes the anonymous first run safe: nothing written on the way in is lost when
     * an account finally appears.
     */
    override suspend fun mergeLocalIntoAccount(
        localOwnerId: String,
        accountOwnerId: String,
    ): Int = withContext(Dispatchers.Default) {
        val local = queries.selectEntriesByOwner(localOwnerId).awaitAsList()
        if (local.isEmpty()) return@withContext 0
        val now = currentTimeMillis()
        queries.updateOwnerId(accountOwnerId, localOwnerId)
        local.forEach { row ->
            queries.markPendingSync(now, row.id)
            queries.enqueue(row.id, OutboxKind.UPSERT.wire, now)
        }
        local.size
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
public class DefaultSyncCoordinator(
    private val syncService: EntrySyncService,
) : SyncCoordinator {
    override fun observeSyncState(): Flow<SyncState> = syncService.state
    override suspend fun syncNow(): Unit = syncService.syncNow()
    override suspend fun syncAfterSignIn(): Unit = syncService.syncAfterSignIn()
    override suspend fun retryFailed(): Unit = syncService.retryFailed()
    override suspend fun dropPendingSync(): Unit = syncService.dropPendingSync()
    override suspend fun dropPendingSyncEntry(id: String): Unit =
        syncService.dropPendingSyncEntry(id)
}
