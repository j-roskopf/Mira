package com.joetr.mira.domain

/**
 * Who is writing.
 *
 * [LocalPending] is the first-run state and is deliberately usable: Mira opens on a cursor, not a
 * login wall, and entries written before signing in are merged into the account afterwards.
 */
public sealed interface SessionState {
    public data class LocalPending(val deviceOwnerId: String) : SessionState
    public data class Anonymous(val userId: String) : SessionState
    public data class Authenticated(val userId: String, val email: String?) : SessionState
}

public val SessionState.ownerId: String
    get() = when (this) {
        is SessionState.LocalPending -> deviceOwnerId
        is SessionState.Anonymous -> userId
        is SessionState.Authenticated -> userId
    }

public enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    public companion object {
        public fun fromStored(value: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}
