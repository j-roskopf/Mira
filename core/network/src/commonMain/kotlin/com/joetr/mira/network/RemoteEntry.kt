package com.joetr.mira.network

/** An entry as it exists in Firestore, independent of the local schema. */
public data class RemoteEntryRow(
    val localDate: String,
    val createdAt: Long,
    val createdTz: String,
    val updatedAt: Long,
    val body: String,
    val attachments: List<RemoteAttachment>,
    val tags: List<String>,
    val deleted: Boolean,
)

public data class RemoteAttachment(
    val id: String,
    val kind: String,
    val storagePath: String,
    val url: String?,
    val mime: String,
    val bytes: Long,
    val durationMs: Long,
    val posterPath: String?,
    val caption: String,
)

public data class FirestoreEntryDocument(
    val id: String,
    val entry: RemoteEntryRow,
)
