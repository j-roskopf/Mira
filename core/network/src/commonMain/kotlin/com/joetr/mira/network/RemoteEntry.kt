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
    val posterUrl: String?,
    val thumbnailPath: String?,
    val thumbnailUrl: String?,
    val caption: String,
)

public data class FirestoreEntryDocument(
    val id: String,
    val entry: RemoteEntryRow,
)

/**
 * The account-wide GitHub archive target, stored at `users/{uid}/meta/archive`.
 *
 * Deliberately holds no credential: the OAuth access token stays on each device, because a
 * `repo`-scoped token in Firestore would grant write access to every repository the user owns.
 * [enabled] lets one device turn the archive off for the whole account.
 */
public data class RemoteArchiveConfig(
    val enabled: Boolean,
    val repoSlug: String?,
    val branch: String = "main",
)
