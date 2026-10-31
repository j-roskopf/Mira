package com.joetr.mira.network

import com.joetr.mira.platform.MiraConfig
import com.joetr.mira.platform.currentTimeMillis
import com.joetr.mira.platform.isNetworkAvailable
import io.ktor.client.HttpClient

public class MiraFirebase(
    public val httpClient: HttpClient,
    private val sessionStore: FirebaseSessionStore,
) {
    public val auth: FirebaseAuthApi = FirebaseAuthApi(httpClient)
    public val firestore: FirestoreApi = FirestoreApi(httpClient)
    public val storage: StorageApi = StorageApi(httpClient)
    public val functions: FunctionsApi = FunctionsApi(httpClient)

    private var cached: FirebaseSession? = null

    public val isConfigured: Boolean
        get() = MiraConfig.FIREBASE_API_KEY.isNotBlank() &&
            MiraConfig.FIREBASE_PROJECT_ID.isNotBlank()

    public suspend fun loadStoredSession(): FirebaseSession? =
        cached ?: sessionStore.load()?.also { cached = it }

    /** Loads persisted credentials into memory before startup work (web localStorage). */
    public suspend fun preloadPersistedSession() {
        if (cached == null) {
            sessionStore.load()?.let { cached = it }
        }
    }

    public suspend fun currentSession(): FirebaseSession? {
        val session = loadStoredSession() ?: return null
        if (session.idToken.isBlank() || session.refreshToken.isBlank() || session.localId.isBlank()) {
            return null
        }
        if (session.expiresAtEpochMs - currentTimeMillis() > 60_000L) return session
        if (!isNetworkAvailable()) return session
        return runCatching { refreshSession(session) }.getOrElse { error ->
            if (error.isNetworkConnectivityError()) session else throw error
        }
    }

    /** Returns a non-expired session with a usable id token for backend API calls. */
    public suspend fun sessionForSync(): FirebaseSession? = currentSession()

    public suspend fun persistAuthenticatedSession(session: FirebaseSession): FirebaseSession {
        require(session.idToken.isNotBlank() && session.refreshToken.isNotBlank() && session.localId.isNotBlank()) {
            "Firebase Auth returned an incomplete session"
        }
        saveSession(session)
        return session
    }

    public suspend fun currentIdToken(): String? = currentSession()?.idToken

    /**
     * Synchronously returns the last known id token without triggering a refresh or
     * a session-store read. Intended for [AuthTokenProvider] implementations that must
     * be non-suspending.
     */
    public fun peekIdToken(): String? = cached?.idToken

    public suspend fun saveSession(session: FirebaseSession?) {
        cached = session
        sessionStore.save(session)
    }

    public suspend fun refreshSession(): FirebaseSession =
        refreshSession(currentSession() ?: error("No session"))

    public suspend fun refreshSession(session: FirebaseSession): FirebaseSession {
        val refreshed = auth.refresh(session.refreshToken).copy(
            email = session.email,
            isAnonymous = session.isAnonymous,
        )
        saveSession(refreshed)
        return refreshed
    }

    public companion object {
        public fun create(
            httpClient: HttpClient,
            sessionStore: FirebaseSessionStore,
        ): MiraFirebase = MiraFirebase(httpClient, sessionStore)
    }
}

public fun interface AuthTokenProvider {
    public fun currentToken(): String?
}
