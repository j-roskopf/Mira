package com.joetr.mira.domain

import kotlinx.serialization.Serializable

/**
 * The export/import bundle format — a locked, versioned contract, independent of [Entry] and
 * [Attachment] so those can keep evolving without breaking old exports.
 *
 * Compatibility rule: a schema version, once shipped, never changes shape. A field that needs to
 * change gets a new optional field with a default (old readers ignore it, old files still decode)
 * or, if the change is not additive, a new [ExportManifest.schemaVersion] with its own parser —
 * never an in-place edit of an existing version's fields.
 */
public const val CURRENT_EXPORT_SCHEMA_VERSION: Int = 1

@Serializable
public data class ExportManifest(
    val schemaVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val entryCount: Int,
    val attachmentCount: Int,
)

/**
 * One entry inside an export bundle.
 *
 * Deliberately not just [Entry]: this type is read back by import, possibly years and several app
 * versions later, so its shape is locked by [CURRENT_EXPORT_SCHEMA_VERSION] rather than drifting
 * with whatever fields [Entry] happens to carry at export time. [ownerId] is intentionally absent —
 * import always writes into whichever account is signed in when it runs.
 */
@Serializable
public data class ExportEntry(
    val id: String,
    val localDate: String,
    val createdAt: Long,
    val createdTz: String,
    val updatedAt: Long,
    val body: String = "",
    val tags: List<String> = emptyList(),
    val attachments: List<ExportAttachment> = emptyList(),
)

/**
 * One attachment inside an export bundle.
 *
 * [mediaPath] points at the file inside the bundle's `media/` directory and is null when the bytes
 * could not be read at export time (e.g. not yet downloaded locally and the device was offline) —
 * the entry still round-trips, just without that recording. Cloud-specific fields like
 * `storagePath`, `url`, and upload `state` are intentionally absent: those belong to whichever
 * account the bundle is imported into, not to the journal content itself.
 */
@Serializable
public data class ExportAttachment(
    val id: String,
    val kind: String,
    val mime: String,
    val bytes: Long = 0,
    val durationMs: Long = 0,
    val caption: String = "",
    val mediaPath: String? = null,
)

@Serializable
public data class ExportBundle(
    val manifest: ExportManifest,
    val entries: List<ExportEntry>,
)

public fun Entry.toExportEntry(archivedMediaPaths: Map<String, String>): ExportEntry = ExportEntry(
    id = id,
    localDate = localDate.value,
    createdAt = createdAt,
    createdTz = createdTz,
    updatedAt = updatedAt,
    body = body,
    tags = tags,
    attachments = attachments.map { it.toExportAttachment(archivedMediaPaths[it.id]) },
)

public fun Attachment.toExportAttachment(mediaPath: String?): ExportAttachment = ExportAttachment(
    id = id,
    kind = kind.name.lowercase(),
    mime = mime,
    bytes = bytes,
    durationMs = durationMs,
    caption = caption,
    mediaPath = mediaPath,
)

public fun ExportEntry.toEntry(ownerId: String): Entry = Entry(
    id = id,
    ownerId = ownerId,
    localDate = LocalDateKey(localDate),
    createdAt = createdAt,
    createdTz = createdTz,
    updatedAt = updatedAt,
    body = body,
    tags = tags,
    attachments = attachments.map { it.toAttachment() },
)

public fun ExportAttachment.toAttachment(): Attachment = Attachment(
    id = id,
    kind = if (kind.equals("video", ignoreCase = true)) AttachmentKind.VIDEO else AttachmentKind.AUDIO,
    storagePath = "",
    mime = mime,
    bytes = bytes,
    durationMs = durationMs,
    caption = caption,
    state = AttachmentState.LOCAL,
)
