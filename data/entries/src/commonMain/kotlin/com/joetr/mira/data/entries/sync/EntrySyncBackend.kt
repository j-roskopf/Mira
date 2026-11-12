package com.joetr.mira.data.entries.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.joetr.mira.data.entries.decodeAttachments
import com.joetr.mira.data.entries.encodeAttachments
import com.joetr.mira.data.entries.encodeTags
import com.joetr.mira.data.entries.toAttachment
import com.joetr.mira.data.entries.toRemoteRow
import com.joetr.mira.db.Entries
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentState
import com.joetr.mira.domain.PendingSyncEntry
import com.joetr.mira.network.FirebaseSession
import com.joetr.mira.network.FirestoreEntryDocument
import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.network.firestoreErrorDetail
import com.joetr.mira.network.isFirestoreNotFound
import com.joetr.mira.network.isFirestoreUnauthenticated
import com.joetr.mira.network.isNetworkConnectivityError
import com.joetr.mira.platform.currentTimeMillis
import com.joetr.mira.platform.isNetworkAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal data class EntrySyncRunResult(
    val pulled: Int,
    val pushed: Int,
    val rejected: Int,
    val errorMessage: String? = null,
)

internal data class EntrySyncOptions(
    /** Always pull, regardless of a pending outbox — used after sign-in and on manual retry. */
    val forcePull: Boolean = false,
)

internal interface EntrySyncBackend {
    suspend fun sync(options: EntrySyncOptions = EntrySyncOptions()): EntrySyncRunResult
    suspend fun resetPullCursor()
    suspend fun pendingCount(): Int
    suspend fun pendingEntries(): List<PendingSyncEntry>
    suspend fun dropPending()
    suspend fun dropPendingEntry(id: String)
}

internal fun createEntrySyncBackend(
    database: MiraDatabase,
    firebase: MiraFirebase,
): EntrySyncBackend = FirebaseEntrySyncBackend(database, firebase)

internal class FirebaseEntrySyncBackend(
    private val database: MiraDatabase,
    private val firebase: MiraFirebase,
) : EntrySyncBackend {

    private val queries get() = database.entriesQueries

    override suspend fun sync(options: EntrySyncOptions): EntrySyncRunResult {
        val session = firebase.sessionForSync() ?: return EntrySyncRunResult(0, 0, 0)
        return runCatching { syncWithSession(session, options) }.getOrElse { error ->
            if (!error.isFirestoreUnauthenticated()) throw error
            if (!isNetworkAvailable()) throw error
            val refreshed = runCatching { firebase.refreshSession(session) }.getOrElse { refreshError ->
                if (refreshError.isNetworkConnectivityError()) throw error
                throw refreshError
            }
            syncWithSession(refreshed, options)
        }
    }

    private suspend fun syncWithSession(
        session: FirebaseSession,
        options: EntrySyncOptions,
    ): EntrySyncRunResult {
        val hadOutboxAtStart = pendingCount() > 0
        // Deletes first, so remote tombstones exist before we reconcile anything from the cloud.
        val deletePush = pushDeletes(session.localId, session.idToken)
        // Skip the pull mid-push unless forced, so we do not read back our own writes.
        val pulled = if (options.forcePull || !hadOutboxAtStart) {
            pull(session.localId, session.idToken)
        } else {
            0
        }
        val upsertPush = pushUpserts(session.localId, session.idToken)
        return EntrySyncRunResult(
            pulled = pulled,
            pushed = deletePush.pushed + upsertPush.pushed,
            rejected = deletePush.rejected + upsertPush.rejected,
            errorMessage = deletePush.errorMessage ?: upsertPush.errorMessage,
        )
    }

    override suspend fun resetPullCursor() {
        io {
            queries.putSetting(PULL_CURSOR_UPDATED_AT, "")
            queries.putSetting(PULL_CURSOR_ID, "")
        }
    }

    override suspend fun pendingCount(): Int = io { queries.selectOutbox().awaitAsList().size }

    override suspend fun pendingEntries(): List<PendingSyncEntry> = io {
        queries.selectOutbox().awaitAsList().map { row ->
            val entry = queries.selectEntryAny(row.entry_id).awaitAsOneOrNull()
            PendingSyncEntry(
                id = row.entry_id,
                label = entry?.local_date ?: row.entry_id,
                kind = row.kind,
            )
        }
    }

    override suspend fun dropPending() {
        io {
            queries.deleteAllOutbox()
            queries.selectPendingEntries().awaitAsList().forEach { queries.clearPendingSync(it.id) }
        }
    }

    override suspend fun dropPendingEntry(id: String) {
        io {
            queries.dequeue(id)
            queries.clearPendingSync(id)
        }
    }

    private suspend fun pull(ownerId: String, idToken: String): Int {
        var cursorUpdatedAt = io {
            queries.getSetting(PULL_CURSOR_UPDATED_AT).awaitAsOneOrNull()?.toLongOrNull()
        }
        var cursorId = io { queries.getSetting(PULL_CURSOR_ID).awaitAsOneOrNull() }?.takeIf { it.isNotBlank() }
        var pulled = 0
        var hasMore = true
        while (hasMore) {
            yield()
            val page = firebase.firestore.pullEntries(
                idToken = idToken,
                ownerId = ownerId,
                cursorUpdatedAt = cursorUpdatedAt,
                cursorId = cursorId,
            )
            if (page.isEmpty()) {
                hasMore = false
            } else {
                page.forEach { mergeRemote(ownerId, it) }
                val last = page.last()
                cursorUpdatedAt = last.entry.updatedAt
                cursorId = last.id
                io {
                    queries.putSetting(PULL_CURSOR_UPDATED_AT, cursorUpdatedAt.toString())
                    queries.putSetting(PULL_CURSOR_ID, cursorId)
                }
                pulled += page.size
                hasMore = page.size >= PAGE_SIZE
            }
        }
        return pulled
    }

    private suspend fun pushDeletes(ownerId: String, idToken: String): PushResult {
        val outbox = io { queries.selectOutbox().awaitAsList() }
        val deleteIds = mutableListOf<String>()
        val orphaned = mutableListOf<String>()

        for (row in outbox) {
            if (row.kind != "DELETE") continue
            val local = io { queries.selectEntryAny(row.entry_id).awaitAsOneOrNull() }
            if (local == null) orphaned += row.entry_id else deleteIds += row.entry_id
        }

        if (orphaned.isNotEmpty()) clearOutbox(orphaned)
        if (deleteIds.isEmpty()) return PushResult()

        try {
            firebase.firestore.markEntriesDeleted(idToken, ownerId, deleteIds)
            clearOutbox(deleteIds)
            advancePullCursor(deleteIds)
            return PushResult(pushed = deleteIds.size)
        } catch (_: Throwable) {
            // Fall through to per-entry deletes so one missing cloud doc cannot block the batch.
        }

        var pushed = 0
        var rejected = 0
        var errorMessage: String? = null
        for (id in deleteIds) {
            try {
                firebase.firestore.markEntriesDeleted(idToken, ownerId, listOf(id))
                clearOutbox(listOf(id))
                advancePullCursor(listOf(id))
                pushed++
            } catch (error: Throwable) {
                if (error.isFirestoreNotFound()) {
                    // Never reached the cloud — nothing to tombstone there.
                    clearOutbox(listOf(id))
                    pushed++
                } else {
                    rejected++
                    errorMessage = error.firestoreErrorDetail()
                }
            }
        }
        return PushResult(pushed = pushed, rejected = rejected, errorMessage = errorMessage)
    }

    private suspend fun pushUpserts(ownerId: String, idToken: String): PushResult {
        val outbox = io { queries.selectOutbox().awaitAsList() }
        if (outbox.isEmpty()) return PushResult()

        val documents = mutableListOf<FirestoreEntryDocument>()
        val ids = mutableListOf<String>()
        val stale = mutableListOf<String>()

        for (row in outbox) {
            if (row.kind == "DELETE") continue
            val local = io { queries.selectEntryAny(row.entry_id).awaitAsOneOrNull() } ?: continue
            if (local.deleted == 1L) {
                stale += local.id
                continue
            }
            documents += FirestoreEntryDocument(id = local.id, entry = local.toRemoteRow())
            ids += local.id
        }

        if (stale.isNotEmpty()) clearOutbox(stale)
        if (documents.isEmpty()) return PushResult()

        return try {
            firebase.firestore.upsertEntries(idToken, ownerId, documents)
            clearOutbox(ids)
            advancePullCursor(ids)
            PushResult(pushed = ids.size)
        } catch (error: Throwable) {
            PushResult(rejected = ids.size, errorMessage = error.firestoreErrorDetail())
        }
    }

    private suspend fun clearOutbox(ids: List<String>) {
        io {
            ids.forEach { id ->
                queries.dequeue(id)
                queries.clearPendingSync(id)
            }
        }
    }

    /** Move the pull cursor past documents we just pushed so the next pull does not re-read them. */
    private suspend fun advancePullCursor(ids: List<String>) {
        if (ids.isEmpty()) return
        val rows = io { ids.mapNotNull { queries.selectEntryAny(it).awaitAsOneOrNull() } }
        val cursorRow = rows.maxWithOrNull(compareBy<Entries> { it.updated_at }.thenBy { it.id })
            ?: return
        val cursorUpdatedAt = maxOf(cursorRow.updated_at, currentTimeMillis())
        io {
            queries.putSetting(PULL_CURSOR_UPDATED_AT, cursorUpdatedAt.toString())
            queries.putSetting(PULL_CURSOR_ID, cursorRow.id)
        }
    }

    private suspend fun mergeRemote(ownerId: String, doc: FirestoreEntryDocument) {
        if (isClockSkewed(doc.entry.updatedAt, currentTimeMillis())) return
        io {
            val pendingKind = queries.selectOutboxKind(doc.id).awaitAsOneOrNull()
            val local = queries.selectEntryAny(doc.id).awaitAsOneOrNull()
            when (remoteMergeAction(pendingKind, local?.updated_at, doc.entry)) {
                RemoteMergeAction.Skip -> return@io
                RemoteMergeAction.ApplyAndClearOutbox -> {
                    queries.dequeue(doc.id)
                    queries.clearPendingSync(doc.id)
                }
                RemoteMergeAction.Apply -> Unit
            }
            val remote = doc.entry
            val remoteAttachments = remote.attachments.map { it.toAttachment() }
            // A remote row can carry a pending attachment published by the device that recorded
            // it. Merge by id so this device's locally staged copy is kept, and so an uploaded
            // copy on either side always wins over a pending placeholder.
            val localAttachments = if (remote.deleted) {
                emptyList()
            } else {
                local?.let { decodeAttachments(it.attachments) }.orEmpty()
            }
            val mergedAttachments = mergeAttachments(remoteAttachments, localAttachments)
            queries.upsertEntry(
                id = doc.id,
                owner_id = ownerId,
                local_date = remote.localDate,
                created_at = remote.createdAt,
                created_tz = remote.createdTz,
                updated_at = remote.updatedAt,
                body = remote.body,
                attachments = encodeAttachments(mergedAttachments),
                tags = encodeTags(remote.tags),
                deleted = if (remote.deleted) 1L else 0L,
                pending_sync = 0L,
            )
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { block() }

    private companion object {
        const val PULL_CURSOR_UPDATED_AT = "firestore_pull_cursor_updated_at"
        const val PULL_CURSOR_ID = "firestore_pull_cursor_id"
        const val PAGE_SIZE = 100
    }
}

private data class PushResult(
    val pushed: Int = 0,
    val rejected: Int = 0,
    val errorMessage: String? = null,
)

/**
 * Combines the attachment list this device pulled with the one it already has.
 *
 * Order of preference per id: a completed upload, then a locally staged copy (so this device can
 * still upload it), then the remote placeholder. Locally staged attachments that the remote row
 * does not mention are kept; completed ones that are absent were deleted on another device.
 */
internal fun mergeAttachments(
    remote: List<Attachment>,
    local: List<Attachment>,
): List<Attachment> {
    val localById = local.associateBy { it.id }
    val merged = remote.map { remoteAttachment ->
        val localAttachment = localById[remoteAttachment.id]
        when {
            remoteAttachment.isUploaded -> remoteAttachment
            localAttachment?.isUploaded == true -> localAttachment
            localAttachment?.state == AttachmentState.LOCAL -> localAttachment
            else -> remoteAttachment
        }
    }
    val remoteIds = remote.mapTo(mutableSetOf()) { it.id }
    // A completed attachment missing from a newer remote row was deleted there; only keep local
    // copies that still need uploading.
    return merged + local.filterNot { it.id in remoteIds || it.isUploaded }
}
