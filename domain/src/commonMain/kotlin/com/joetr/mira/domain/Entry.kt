package com.joetr.mira.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A journal entry.
 *
 * There is exactly one entry type. An entry is text that may carry attachments — a voice memo is an
 * entry with an empty [body] and one audio attachment. The UI offers three ways to create one; they
 * all produce this.
 *
 * [localDate] is the calendar day the entry belongs to, decided at creation from the device's zone
 * and **never recomputed**. Deriving it from [createdAt] would silently move entries between days
 * when travelling, or render them differently on a device in another zone. [createdAt] and
 * [createdTz] exist for ordering and for showing the real wall-clock time.
 */
@Serializable
public data class Entry(
    val id: String,
    val ownerId: String,
    val localDate: LocalDateKey,
    val createdAt: Long,
    val createdTz: String,
    val updatedAt: Long,
    val body: String = "",
    val attachments: List<Attachment> = emptyList(),
    val tags: List<String> = emptyList(),
    val deleted: Boolean = false,
) {
    public val hasAudio: Boolean get() = attachments.any { it.kind == AttachmentKind.AUDIO }
    public val hasVideo: Boolean get() = attachments.any { it.kind == AttachmentKind.VIDEO }
    public val hasMedia: Boolean get() = attachments.isNotEmpty()

    /** True while any attachment is still only on this device. */
    public val hasPendingUpload: Boolean
        get() = attachments.any { it.state != AttachmentState.UPLOADED }

    /** Leading text for timeline rows, falling back to a description of the media. */
    public fun excerpt(maxChars: Int = 160): String {
        val trimmed = body.trim()
        if (trimmed.isNotEmpty()) {
            return if (trimmed.length <= maxChars) trimmed else trimmed.take(maxChars).trimEnd() + "…"
        }
        val audio = attachments.count { it.kind == AttachmentKind.AUDIO }
        val video = attachments.count { it.kind == AttachmentKind.VIDEO }
        return when {
            video > 0 && audio > 0 -> "Video and audio"
            video == 1 -> "Video"
            video > 1 -> "$video videos"
            audio == 1 -> "Voice note"
            audio > 1 -> "$audio voice notes"
            else -> ""
        }
    }
}

@Serializable
public enum class AttachmentKind {
    @SerialName("audio")
    AUDIO,

    @SerialName("video")
    VIDEO,
}

/**
 * Where an attachment's bytes currently are.
 *
 * An entry row syncs instantly while its media may not have uploaded yet, so an attachment can
 * reach another device before its bytes do. That state is rendered, not treated as an error.
 */
@Serializable
public enum class AttachmentState {
    @SerialName("local")
    LOCAL,

    @SerialName("uploading")
    UPLOADING,

    @SerialName("uploaded")
    UPLOADED,
}

/**
 * One piece of media on an entry.
 *
 * Serialized as JSON into `entries.attachments` rather than kept in a child table: the sync engine
 * merges whole rows, so a child table would let an entry arrive on another device without its
 * attachments.
 *
 * [storagePath] is the durable identifier. [url] is a convenience that can rot — download tokens
 * are revocable — and is always re-derivable from the path.
 */
@Serializable
public data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val storagePath: String,
    val url: String? = null,
    val mime: String,
    val bytes: Long = 0,
    val durationMs: Long = 0,
    val posterPath: String? = null,
    val caption: String = "",
    val state: AttachmentState = AttachmentState.LOCAL,
) {
    public val isUploaded: Boolean get() = state == AttachmentState.UPLOADED
}

/** Entry counts for one day, used to colour the calendar heatmap. */
public data class DayActivity(
    val date: LocalDateKey,
    val entryCount: Int,
)
