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
 * Where an attachment ended up, which may be several files.
 *
 * Large recordings are split into ordered parts when they cannot be reduced below the blob limit.
 * The parts are byte ranges of one file, so concatenation restores it exactly.
 */
public data class ArchivedMedia(val paths: List<String>) {
    public constructor(path: String) : this(listOf(path))
}

/** Convenience for callers that only know which single paths were archived. */
public fun Entry.toMarkdown(archivedMediaPaths: Set<String>): String = toMarkdown(
    attachments.mapNotNull { attachment ->
        val path = attachment.archiveMediaPath(this)
        if (path in archivedMediaPaths) attachment.id to ArchivedMedia(path) else null
    }.toMap(),
)

/**
 * Serializes an entry as Markdown with YAML frontmatter.
 *
 * The format is chosen to still be readable in twenty years without Mira: frontmatter parses in
 * every language, the body is plain text, and the filename sorts chronologically. If the app
 * disappears, the journal is a folder of Markdown.
 */
@OptIn(ExperimentalTime::class)
public fun Entry.toMarkdown(
    archivedMedia: Map<String, ArchivedMedia> = emptyMap(),
): String = buildString {
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
    val linked = attachments.mapNotNull { attachment ->
        archivedMedia[attachment.id]?.takeIf { it.paths.isNotEmpty() }?.let { attachment to it }
    }
    if (linked.isNotEmpty()) {
        appendLine()
        appendLine("## Recordings")
        appendLine()
        linked.forEachIndexed { index, (attachment, media) ->
            val label = when (attachment.kind) {
                AttachmentKind.VIDEO -> "Watch video"
                AttachmentKind.IMAGE -> "View photo"
                AttachmentKind.AUDIO -> "Listen to audio"
            }
            if (media.paths.size == 1) {
                appendLine("- [$label ${index + 1}](${rootLink(media.paths.single())})")
            } else {
                appendLine("- [$label ${index + 1}] (split into ${media.paths.size} parts)")
                media.paths.forEachIndexed { partIndex, path ->
                    appendLine("  - [part ${partIndex + 1}](${rootLink(path)})")
                }
            }
        }
        if (linked.any { (_, media) -> media.paths.size > 1 }) {
            appendLine()
            appendLine(
                "_Split recordings are byte ranges of one file. Concatenate the parts in order " +
                    "(for example `cat part*.mp4 > video.mp4`) to restore the original._",
            )
        }
    }
}

/** Notes live in entries/YYYY/MM; walk back to the repository root. */
private fun rootLink(path: String): String = "../../../$path"

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
public fun Attachment.archiveMediaPath(entry: Entry): String =
    "media/${entry.yearMonthSegment()}/${entry.id}/$id.${archiveExtension()}"

/**
 * Part [index] (one-based) of a recording split for the blob limit.
 *
 * Padding follows the part count so a lexical listing stays in order.
 */
public fun Attachment.archivePartPath(entry: Entry, index: Int, total: Int): String {
    val base = archiveMediaPath(entry)
    val extension = archiveExtension()
    val stem = base.removeSuffix(".$extension")
    val padding = total.toString().length.coerceAtLeast(3)
    return "$stem.part${index.toString().padStart(padding, '0')}.$extension"
}

private fun Entry.yearMonthSegment(): String =
    localDate.value.substring(0, 4) + "/" + localDate.value.substring(5, 7)

private fun Attachment.archiveExtension(): String = when (kind) {
    AttachmentKind.VIDEO -> "mp4"
    AttachmentKind.AUDIO -> "m4a"
    AttachmentKind.IMAGE -> "jpg"
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
