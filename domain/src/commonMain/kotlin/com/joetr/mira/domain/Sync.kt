package com.joetr.mira.domain

public enum class SyncStatus {
    SYNCED,
    SYNCING,
    PENDING,
    ERROR,
}

public data class PendingSyncEntry(
    val id: String,
    val label: String,
    val kind: String,
)

public data class SyncState(
    val status: SyncStatus = SyncStatus.SYNCED,
    val pendingCount: Int = 0,
    val errorMessage: String? = null,
    val pendingEntries: List<PendingSyncEntry> = emptyList(),
)

/** What a local change should do to the outbox. */
public enum class OutboxKind {
    UPSERT,
    DELETE,
    ;

    public val wire: String get() = name

    public companion object {
        public fun fromWire(raw: String?): OutboxKind? =
            entries.firstOrNull { it.name == raw }
    }
}
