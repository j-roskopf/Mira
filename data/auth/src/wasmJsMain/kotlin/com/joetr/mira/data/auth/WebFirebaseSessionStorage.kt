package com.joetr.mira.data.auth

import com.joetr.mira.network.FirebaseSession
import kotlinx.browser.localStorage

private const val PREFIX = "mira_session."

/**
 * Plain localStorage fields for web auth — avoids kotlinx JSON issues on wasm and
 * survives refresh without depending on the in-memory SQL worker.
 */
internal object WebFirebaseSessionStorage {
    fun save(session: FirebaseSession?) {
        if (session == null) {
            clear()
            return
        }
        localStorage.setItem(PREFIX + "localId", session.localId)
        localStorage.setItem(PREFIX + "idToken", session.idToken)
        localStorage.setItem(PREFIX + "refreshToken", session.refreshToken)
        localStorage.setItem(PREFIX + "expiresAt", session.expiresAtEpochMs.toString())
        localStorage.setItem(PREFIX + "isAnonymous", if (session.isAnonymous) "1" else "0")
        val email = session.email
        if (!email.isNullOrBlank()) {
            localStorage.setItem(PREFIX + "email", email)
        } else {
            localStorage.removeItem(PREFIX + "email")
        }
    }

    fun load(): FirebaseSession? {
        val fromKeys = loadFromKeys()
        if (fromKeys != null) return fromKeys

        val legacy = localStorage.getItem("mira_firebase_session")
        if (!legacy.isNullOrBlank()) {
            val migrated = runCatching {
                firebaseSessionJson.decodeFromString(FirebaseSession.serializer(), legacy)
            }.getOrNull()
            if (migrated != null) {
                save(migrated)
                return migrated
            }
        }
        return null
    }

    private fun loadFromKeys(): FirebaseSession? {
        val localId = localStorage.getItem(PREFIX + "localId")
        val idToken = localStorage.getItem(PREFIX + "idToken")
        val refreshToken = localStorage.getItem(PREFIX + "refreshToken")
        val expiresAt = localStorage.getItem(PREFIX + "expiresAt")?.toLongOrNull()
        if (localId.isNullOrBlank() || idToken.isNullOrBlank() || refreshToken.isNullOrBlank() || expiresAt == null) {
            return null
        }
        val isAnonymous = localStorage.getItem(PREFIX + "isAnonymous") == "1"
        val email = localStorage.getItem(PREFIX + "email")
        return FirebaseSession(
            localId = localId,
            email = email?.takeIf { it.isNotBlank() },
            idToken = idToken,
            refreshToken = refreshToken,
            expiresAtEpochMs = expiresAt,
            isAnonymous = isAnonymous,
        )
    }

    private fun clear() {
        localStorage.removeItem(PREFIX + "localId")
        localStorage.removeItem(PREFIX + "idToken")
        localStorage.removeItem(PREFIX + "refreshToken")
        localStorage.removeItem(PREFIX + "expiresAt")
        localStorage.removeItem(PREFIX + "isAnonymous")
        localStorage.removeItem(PREFIX + "email")
        // Legacy JSON blob from earlier attempts.
        localStorage.removeItem("mira_firebase_session")
    }
}
