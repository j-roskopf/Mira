package com.joetr.mira.data.archive

import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.Entry
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime

/**
 * Serializes an entry as Markdown with YAML frontmatter.
 *
 * The format is chosen to still be readable in twenty years without Mira: frontmatter parses in
 * every language, the body is plain text, and the filename sorts chronologically. If the app
 * disappears, the journal is a folder of Markdown.
 */
@OptIn(ExperimentalTime::class)
public fun Entry.toMarkdown(archivedMediaPaths: Set<String> = emptySet()): String = buildString {
    appendLine("---")
    appendLine("id: $id")
    appendLine("date: ${localDate.value}")
    appendLine("created_at: ${isoWithOffset(createdAt, createdTz)}")
    appendLine("tz: $createdTz")
    if (tags.isNotEmpty()) {
        appendLine("tags: [${tags.joinToString(", ")}]")
    }
    if (attachments.isNotEmpty()) {
        appendLine("attachments:")
        attachments.forEach { attachment ->
            appendLine("  - kind: ${attachment.kind.name.lowercase()}")
            appendLine("    storage: ${attachment.storagePath}")
            attachment.url?.let { appendLine("    url: $it") }
            appendLine("    mime: ${attachment.mime}")
            if (attachment.durationMs > 0) {
                appendLine("    duration_ms: ${attachment.durationMs}")
            }
            if (attachment.bytes > 0) {
                appendLine("    bytes: ${attachment.bytes}")
            }
            if (attachment.caption.isNotBlank()) {
                appendLine("    caption: ${attachment.caption.escapeYaml()}")
            }
        }
    }
    appendLine("---")
    appendLine()
    appendLine(body.trim())
    val linked = attachments.filter { it.archiveMediaPath(this@toMarkdown) in archivedMediaPaths }
    if (linked.isNotEmpty()) {
        appendLine()
        appendLine("## Recordings")
        appendLine()
        linked.forEachIndexed { index, attachment ->
            val label = when (attachment.kind) {
                AttachmentKind.VIDEO -> "Watch video"
                AttachmentKind.IMAGE -> "View photo"
                AttachmentKind.AUDIO -> "Listen to audio"
            }
            // Notes live in entries/YYYY/MM; walk back to the repository root.
            val path = "../../../${attachment.archiveMediaPath(this@toMarkdown)}"
            appendLine("- [$label ${index + 1}]($path)")
        }
    }
}

/**
 * Path for an entry inside the archive repository.
 *
 * Date-partitioned so a directory listing stays usable after a decade, and prefixed with the time
 * so a day's entries sort in the order they were written.
 */
@OptIn(ExperimentalTime::class)
public fun Entry.archivePath(): String {
    val year = localDate.value.substring(0, 4)
    val month = localDate.value.substring(5, 7)
    val time = runCatching {
        val local = Instant.fromEpochMilliseconds(createdAt)
            .toLocalDateTime(TimeZone.of(createdTz))
        buildString {
            append(local.hour.toString().padStart(2, '0'))
            append(local.minute.toString().padStart(2, '0'))
            append(local.second.toString().padStart(2, '0'))
        }
    }.getOrDefault("000000")
    val shortId = id.takeLast(4)
    return "entries/$year/$month/${localDate.value}--$time--$shortId.md"
}

/** Where an attachment's bytes live when media is included in the archive. */
public fun Attachment.archiveMediaPath(entry: Entry): String {
    val year = entry.localDate.value.substring(0, 4)
    val month = entry.localDate.value.substring(5, 7)
    val extension = when (kind) {
        AttachmentKind.VIDEO -> "mp4"
        AttachmentKind.AUDIO -> "m4a"
        AttachmentKind.IMAGE -> "jpg"
    }
    return "media/$year/$month/${entry.id}/$id.$extension"
}

@OptIn(ExperimentalTime::class)
private fun isoWithOffset(epochMillis: Long, zoneId: String): String = runCatching {
    val zone = TimeZone.of(zoneId)
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val local = instant.toLocalDateTime(zone)
    val offsetSeconds = zone.offsetAt(instant).totalSeconds
    val sign = if (offsetSeconds < 0) "-" else "+"
    val absolute = if (offsetSeconds < 0) -offsetSeconds else offsetSeconds
    val hours = (absolute / 3600).toString().padStart(2, '0')
    val minutes = ((absolute % 3600) / 60).toString().padStart(2, '0')
    "$local$sign$hours:$minutes"
}.getOrElse { Instant.fromEpochMilliseconds(epochMillis).toString() }

private fun String.escapeYaml(): String =
    if (any { it in ":#[]{}," } || trim() != this) "\"${replace("\"", "\\\"")}\"" else this
