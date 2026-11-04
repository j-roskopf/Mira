package com.joetr.mira.data.entries

import com.joetr.mira.db.Entries
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.AttachmentState
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.network.RemoteAttachment
import com.joetr.mira.network.RemoteEntryRow
import kotlinx.serialization.json.Json

internal val entryJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun Entries.toEntry(): Entry = Entry(
    id = id,
    ownerId = owner_id,
    localDate = LocalDateKey(local_date),
    createdAt = created_at,
    createdTz = created_tz,
    updatedAt = updated_at,
    body = body,
    attachments = decodeAttachments(attachments),
    tags = decodeTags(tags),
    deleted = deleted == 1L,
)

internal fun decodeAttachments(raw: String): List<Attachment> =
    runCatching { entryJson.decodeFromString<List<Attachment>>(raw) }.getOrElse { emptyList() }

internal fun encodeAttachments(attachments: List<Attachment>): String =
    entryJson.encodeToString(attachments)

internal fun decodeTags(raw: String): List<String> =
    runCatching { entryJson.decodeFromString<List<String>>(raw) }.getOrElse { emptyList() }

internal fun encodeTags(tags: List<String>): String = entryJson.encodeToString(tags)

internal fun Entries.toRemoteRow(): RemoteEntryRow = RemoteEntryRow(
    localDate = local_date,
    createdAt = created_at,
    createdTz = created_tz,
    updatedAt = updated_at,
    body = body,
    // Only publish attachments whose bytes are already in Storage. If an upload failed we still
    // push the text now and republish the entry once the attachment lands, so another device
    // never receives a reference it cannot open.
    attachments = decodeAttachments(attachments)
        .filter { it.isUploaded && it.storagePath.isNotBlank() }
        .map { it.toRemote() },
    tags = decodeTags(tags),
    deleted = deleted == 1L,
)

internal fun Attachment.toRemote(): RemoteAttachment = RemoteAttachment(
    id = id,
    kind = kind.wire,
    storagePath = storagePath,
    url = url,
    mime = mime,
    bytes = bytes,
    durationMs = durationMs,
    posterPath = posterPath,
    caption = caption,
)

/**
 * Remote attachments are always [AttachmentState.UPLOADED]: an attachment only reaches Firestore
 * after its bytes are in Storage, so anything arriving from the cloud is by definition complete.
 */
internal fun RemoteAttachment.toAttachment(): Attachment = Attachment(
    id = id,
    kind = attachmentKindFromWire(kind),
    storagePath = storagePath,
    url = url,
    mime = mime,
    bytes = bytes,
    durationMs = durationMs,
    posterPath = posterPath,
    caption = caption,
    state = AttachmentState.UPLOADED,
)

internal val AttachmentKind.wire: String
    get() = when (this) {
        AttachmentKind.AUDIO -> "audio"
        AttachmentKind.VIDEO -> "video"
        AttachmentKind.IMAGE -> "image"
    }

internal fun attachmentKindFromWire(raw: String): AttachmentKind = when {
    raw.equals("video", ignoreCase = true) -> AttachmentKind.VIDEO
    raw.equals("image", ignoreCase = true) -> AttachmentKind.IMAGE
    else -> AttachmentKind.AUDIO
}
