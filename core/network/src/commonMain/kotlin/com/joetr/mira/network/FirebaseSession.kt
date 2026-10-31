package com.joetr.mira.network

import kotlinx.serialization.Serializable

@Serializable
public data class FirebaseSession(
    val localId: String,
    val email: String? = null,
    val idToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val isAnonymous: Boolean,
)

public interface FirebaseSessionStore {
    public suspend fun load(): FirebaseSession?
    public suspend fun save(session: FirebaseSession?)
}
