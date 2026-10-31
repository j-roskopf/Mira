package com.joetr.mira.data.entries.sync

import com.joetr.mira.network.RemoteEntryRow

internal enum class RemoteMergeAction {
    /** Keep local state; the remote change is ignored. */
    Skip,

    /** Apply the remote row as-is. */
    Apply,

    /** Drop a stale local outbox entry, then apply the remote row. */
    ApplyAndClearOutbox,
}

/**
 * Decides whether a pulled remote entry should be merged into the local database.
 *
 * Whole-row last-write-wins on `updatedAt`, with two deliberate asymmetries:
 *
 * - **Remote deletes always win** over a stale local upsert, so deleting an entry on one device
 *   propagates even if another device has an unsynced edit of it queued.
 * - **A local pending edit blocks a remote non-delete**, so an edit made offline is not silently
 *   overwritten by the version it was edited from before it has had a chance to push.
 *
 * Anything lost to this is still recoverable: the GitHub archive commits every version, so
 * `git log` on an entry file is its revision history.
 */
internal fun remoteMergeAction(
    pendingKind: String?,
    localUpdatedAt: Long?,
    remote: RemoteEntryRow,
): RemoteMergeAction {
    if (remote.deleted) {
        if (pendingKind == "DELETE") return RemoteMergeAction.Skip
        if (pendingKind != null) return RemoteMergeAction.ApplyAndClearOutbox
        return RemoteMergeAction.Apply
    }
    if (pendingKind != null) return RemoteMergeAction.Skip
    if (localUpdatedAt != null && localUpdatedAt > remote.updatedAt) return RemoteMergeAction.Skip
    return RemoteMergeAction.Apply
}

/**
 * Rejects remote rows stamped implausibly far in the future.
 *
 * `updatedAt` is written by whichever device made the change, so a device with a badly wrong clock
 * could otherwise stamp a row that wins every future merge permanently.
 */
internal fun isClockSkewed(remoteUpdatedAt: Long, now: Long): Boolean =
    remoteUpdatedAt > now + MAX_FUTURE_SKEW_MS

private const val MAX_FUTURE_SKEW_MS: Long = 24L * 60 * 60 * 1000
