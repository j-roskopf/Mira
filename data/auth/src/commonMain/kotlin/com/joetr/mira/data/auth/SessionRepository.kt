package com.joetr.mira.data.auth

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.model.SessionState
import com.joetr.mira.domain.repository.RecipeRepository
import com.joetr.mira.domain.repository.SessionRepository
import com.joetr.mira.domain.repository.SyncRepository
import com.joetr.mira.network.AuthTokenProvider
import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.network.FirebaseSession
import com.joetr.mira.network.FirebaseSessionStore
import com.joetr.mira.network.isNetworkConnectivityError
import com.joetr.mira.platform.currentTimeMillis
import com.joetr.mira.platform.isNetworkAvailable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
public class DefaultSessionRepository(
    private val database: MiraDatabase,
    private val recipeRepository: RecipeRepository,
    private val firebase: MiraFirebase,
    private val syncRepository: SyncRepository,
) : SessionRepository {
    private val session = MutableStateFlow<SessionState>(SessionState.LocalPending(""))
    private var pendingMergeLocalOwnerId: String? = null

    override fun observeSession(): Flow<SessionState> = session.asStateFlow()

    override suspend fun ensureSession() {
        if (!firebase.isConfigured) {
            val deviceOwnerId = readOrCreateDeviceOwnerId()
            if (session.value !is SessionState.Authenticated) {
                session.value = SessionState.LocalPending(deviceOwnerId)
            }
            promoteStoredFirebaseSession()
            return
        }

        // Restore from localStorage (web) before touching DB or creating anonymous users.
        val persisted = firebase.loadStoredSession()

        val deviceOwnerId = readOrCreateDeviceOwnerId()
        if (session.value !is SessionState.Authenticated) {
            session.value = SessionState.LocalPending(deviceOwnerId)
        }

        if (!isNetworkAvailable()) {
            promoteStoredFirebaseSession()
            return
        }

        if (persisted != null && !persisted.isAnonymous) {
            val active = resolveStoredSession(persisted)
            if (active != null) {
                firebase.saveSession(active)
                withContext(Dispatchers.Default) {
                    database.recipesQueries.updateOwnerId(active.localId, deviceOwnerId)
                }
                session.value = SessionState.Authenticated(active.localId, active.email)
                return
            }
        }

        val stored = if (persisted != null && !persisted.isAnonymous) null else persisted
        val active: FirebaseSession? = if (stored == null) {
            signInAnonymously()
        } else {
            resolveStoredSession(stored) ?: signInAnonymously()
        }
        if (active == null) {
            promoteStoredFirebaseSession()
            return
        }

        withContext(Dispatchers.Default) {
            database.recipesQueries.updateOwnerId(active.localId, deviceOwnerId)
        }
        session.value = if (active.isAnonymous) {
            SessionState.Anonymous(active.localId)
        } else {
            SessionState.Authenticated(active.localId, active.email)
        }
    }

    override suspend fun resumePendingWebOAuth(): Boolean {
        val result = resumeWebOAuthSignIn(firebase) ?: return false
        applyAuthenticatedSession(session.value, result.userId, result.email)
        return true
    }

    override suspend fun signInWithEmail(email: String, password: String) {
        val prior = session.value
        val newSession = signInWithEmailPassword(firebase, email, password)
        val active = firebase.persistAuthenticatedSession(newSession)
        handlePostSignIn(prior, active.localId, email)
    }

    override suspend fun signUpWithEmail(email: String, password: String) {
        val prior = session.value
        val newSession = if (prior is SessionState.Anonymous) {
            signUpFromAnonymousSession(firebase, email, password)
        } else {
            signUpWithEmailPassword(firebase, email, password)
        }
        val active = firebase.persistAuthenticatedSession(newSession)
        handlePostSignIn(prior, active.localId, email)
    }

    override suspend fun resetPassword(email: String) {
        runFirebaseAuthCall { firebase.auth.sendPasswordResetEmail(email) }
    }

    override suspend fun signInWithGoogle() {
        val prior = session.value
        val result = googleSignIn(firebase)
        handlePostSignIn(prior, result.userId, result.email)
    }

    override suspend fun signOut() {
        withContext(Dispatchers.Default) {
            database.recipesQueries.deleteAllRecipes()
            database.recipesQueries.deleteAllImageBlobs()
        }
        pendingMergeLocalOwnerId = null
        firebase.saveSession(null)
        ensureSession()
    }

    override suspend fun needsMergePrompt(): Pair<Boolean, Int> {
        val localId = pendingMergeLocalOwnerId ?: return false to 0
        return true to recipeRepository.countByOwner(localId)
    }

    override suspend fun acceptMerge(): Int {
        val localId = pendingMergeLocalOwnerId ?: return 0
        val accountId = (session.value as? SessionState.Authenticated)?.userId ?: return 0
        val count = recipeRepository.mergeLocalIntoAccount(localId, accountId)
        pendingMergeLocalOwnerId = null
        return count
    }

    override suspend fun declineMerge() {
        val localId = pendingMergeLocalOwnerId ?: return
        withContext(Dispatchers.Default) {
            database.recipesQueries.selectRecipesByOwner(localId).awaitAsList().forEach {
                database.recipesQueries.softDeleteRecipe(currentTimeMillis(), it.id)
            }
        }
        pendingMergeLocalOwnerId = null
    }

    internal fun currentToken(): String? = firebase.peekIdToken()

    private suspend fun signInAnonymously(): FirebaseSession? =
        runCatching {
            val anon = runFirebaseAuthCall { firebase.auth.signInAnonymously() }
            firebase.saveSession(anon)
            anon
        }.getOrElse { error ->
            if (error.isNetworkConnectivityError()) null else throw error
        }

    private suspend fun resolveStoredSession(stored: FirebaseSession): FirebaseSession? {
        if (stored.idToken.isBlank() || stored.refreshToken.isBlank() || stored.localId.isBlank()) {
            firebase.saveSession(null)
            return null
        }
        if (stored.expiresAtEpochMs - currentTimeMillis() > 60_000L) {
            return stored
        }
        return runCatching {
            runFirebaseAuthCall { firebase.refreshSession(stored) }
        }.getOrElse { error ->
            when {
                isUnrecoverableSessionError(error) -> {
                    firebase.saveSession(null)
                    null
                }
                error.isNetworkConnectivityError() -> stored
                else -> throw error
            }
        }
    }

    private suspend fun promoteStoredFirebaseSession() {
        val stored = firebase.loadStoredSession() ?: return
        session.value = if (stored.isAnonymous) {
            SessionState.Anonymous(stored.localId)
        } else {
            SessionState.Authenticated(stored.localId, stored.email)
        }
    }

    private suspend fun applyAuthenticatedSession(prior: SessionState, userId: String, email: String?) {
        val priorOwnerId = ownerIdOf(prior)
        if (priorOwnerId != null && priorOwnerId != userId) {
            pendingMergeLocalOwnerId = priorOwnerId
        }
        session.value = SessionState.Authenticated(userId, email)
    }

    /**
     * Marks [prior]'s local owner id for a merge prompt if the newly authenticated
     * [userId] doesn't match it (e.g. a Google credential link failed and fell back to
     * a fresh account, or the user signed in to a pre-existing account while local-only
     * recipes were still tracked under an anonymous/device owner id).
     */
    private suspend fun handlePostSignIn(prior: SessionState, userId: String, email: String?) {
        applyAuthenticatedSession(prior, userId, email)
        syncRepository.syncAfterSignIn()
    }

    private fun ownerIdOf(state: SessionState): String? = when (state) {
        is SessionState.Anonymous -> state.userId
        is SessionState.LocalPending -> state.deviceOwnerId
        is SessionState.Authenticated -> null
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun readOrCreateDeviceOwnerId(): String {
        val key = "device_owner_id"
        val existing = withContext(Dispatchers.Default) {
            database.recipesQueries.selectSetting(key).awaitAsOneOrNull()
        }
        if (!existing.isNullOrBlank()) return existing
        val id = Uuid.random().toString()
        withContext(Dispatchers.Default) {
            database.recipesQueries.upsertSetting(key, id)
        }
        return id
    }
}

public data class GoogleSignInResult(val userId: String, val email: String?)

public expect suspend fun googleSignIn(firebase: MiraFirebase): GoogleSignInResult

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
public class SessionAuthTokenProvider(
    private val sessionRepository: DefaultSessionRepository,
) : AuthTokenProvider {
    override fun currentToken(): String? = sessionRepository.currentToken()
}

/**
 * Persists the current [FirebaseSession] as serialized JSON in the `app_settings`
 * key/value table so it survives app restarts. There is no dedicated delete query, so
 * a `null` session is stored as a blank string and treated as absent on [load].
 */
public class DatabaseFirebaseSessionStore(
    private val database: MiraDatabase,
) : FirebaseSessionStore {
    override suspend fun load(): FirebaseSession? {
        readPlatformFirebaseSession()?.let { return it }
        return withContext(Dispatchers.Default) {
            val fromDb = database.recipesQueries.selectSetting(SETTING_KEY).awaitAsOneOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching { firebaseSessionJson.decodeFromString(FirebaseSession.serializer(), raw) }.getOrNull()
                }
            fromDb
        }
    }

    override suspend fun save(session: FirebaseSession?) {
        // Write localStorage first so auth survives even if the SQL worker is busy.
        writePlatformFirebaseSession(session)
        withContext(Dispatchers.Default) {
            val raw = if (session == null) "" else firebaseSessionJson.encodeToString(FirebaseSession.serializer(), session)
            database.recipesQueries.upsertSetting(SETTING_KEY, raw)
        }
    }

    private companion object {
        private const val SETTING_KEY = "firebase_session"
    }
}

internal val firebaseSessionJson = Json { ignoreUnknownKeys = true }
