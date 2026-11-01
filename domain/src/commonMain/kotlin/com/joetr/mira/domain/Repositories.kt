package com.joetr.mira.domain

import kotlinx.coroutines.flow.Flow

public interface SessionRepository {
    public fun observeSession(): Flow<SessionState>
    public suspend fun ensureSession()
    public suspend fun resumePendingWebOAuth(): Boolean
    public suspend fun signInWithEmail(email: String, password: String)
    public suspend fun signUpWithEmail(email: String, password: String)
    public suspend fun resetPassword(email: String)
    public suspend fun signInWithGoogle()
    public suspend fun signOut()

    /** Whether local entries exist that could be merged into a newly signed-in account. */
    public suspend fun needsMergePrompt(): Pair<Boolean, Int>
    public suspend fun acceptMerge(): Int
    public suspend fun declineMerge()
}

/** The entry operations the session layer needs, without depending on the data module. */
public interface EntryStore {
    public suspend fun countByOwner(ownerId: String): Int
    public suspend fun mergeLocalIntoAccount(localOwnerId: String, accountOwnerId: String): Int
}

public interface SyncCoordinator {
    public fun observeSyncState(): Flow<SyncState>
    public suspend fun syncNow()
    public suspend fun syncAfterSignIn()
    public suspend fun retryFailed()
    public suspend fun dropPendingSync()
    public suspend fun dropPendingSyncEntry(id: String)
}

public interface UserSettingsRepository {
    public fun observeThemeMode(): Flow<ThemeMode>
    public suspend fun setThemeMode(mode: ThemeMode)

    /** The chosen accent, stored by [MiraAccent] name. */
    public fun observeAccent(): Flow<String>
    public suspend fun setAccent(name: String)

    public fun observeMediaCacheCapBytes(): Flow<Long>
    public suspend fun setMediaCacheCapBytes(bytes: Long)

    /** Whether video uploads wait for an unmetered network. */
    public fun observeVideoUploadOnUnmeteredOnly(): Flow<Boolean>
    public suspend fun setVideoUploadOnUnmeteredOnly(enabled: Boolean)

    /** Whether the GitHub archive commits media files as files as well as links. */
    public fun observeIncludeMediaInArchive(): Flow<Boolean>
    public suspend fun setIncludeMediaInArchive(enabled: Boolean)
}
