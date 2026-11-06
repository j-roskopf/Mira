package com.joetr.mira.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.media.AttachmentImage
import com.joetr.mira.media.AttachmentImageState
import com.joetr.mira.media.MediaSource
import com.joetr.mira.media.locationLabel
import com.joetr.mira.ui.components.EmptyState
import com.joetr.mira.ui.components.MediaInfoButton
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.displayDate
import com.joetr.mira.ui.displayName
import com.joetr.mira.ui.formatDuration
import com.joetr.mira.ui.formatTimestamp
import com.joetr.mira.ui.icons.MiraIcon
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.shortName
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme
import com.joetr.mira.ui.weekdayUpper

private val TileGap = MiraSpacing.sm

public data class MediaPiece(val entry: Entry, val attachment: Attachment)

private data class MediaDay(val date: LocalDateKey, val pieces: List<MediaPiece>)

private data class MediaMonth(val month: MonthKey, val days: List<MediaDay>) {
    val pieceCount: Int get() = days.sumOf { it.pieces.size }
}

private data class MediaArchive(
    val months: List<MediaMonth>,
    val photos: Int,
    val videos: Int,
    val voiceNotes: Int,
    val firstDate: LocalDateKey?,
) {
    val isEmpty: Boolean get() = months.isEmpty()

    fun summary(): String {
        val parts = buildList {
            if (photos > 0) add("$photos ${if (photos == 1) "frame" else "frames"}")
            if (videos > 0) add("$videos ${if (videos == 1) "film" else "films"}")
            if (voiceNotes > 0) {
                add("$voiceNotes ${if (voiceNotes == 1) "voice note" else "voice notes"}")
            }
        }
        return when (parts.size) {
            0 -> ""
            1 -> parts.single()
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }
}

@Composable
public fun MediaScreen(
    entries: List<Entry>,
    onEntryClick: (Entry) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    thumbnailFor: suspend (Attachment) -> MediaSource? = { null },
) {
    val colors = MiraTheme.colors
    val archive = remember(entries) { buildArchive(entries) }

    BoxWithConstraints(modifier.fillMaxSize().background(colors.canvas)) {
        val wide = maxWidth >= 720.dp
        val gutterWidth = if (wide) 96.dp else 58.dp
        val numeralSize = if (wide) 42.sp else 30.sp

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MiraSpacing.gutter,
                end = MiraSpacing.gutter,
                top = MiraSpacing.md,
                bottom = MiraSpacing.giant,
            ),
        ) {
            item(key = "archive-header") {
                ArchiveHeader(archive = archive, loading = loading)
            }

            if (!loading && archive.isEmpty) {
                item(key = "archive-empty") {
                    EmptyState("Nothing captured yet.")
                    Text(
                        text = "Photos, videos and voice notes will gather here.",
                        style = MiraTheme.type.uiBody,
                        color = colors.inkMuted,
                    )
                }
            }

            archive.months.forEach { month ->
                stickyHeader(key = "month-${month.month.value}") {
                    MonthHeader(month)
                }
                month.days.forEach { day ->
                    item(key = "day-${day.date.value}") {
                        DayCluster(
                            day = day,
                            gutterWidth = gutterWidth,
                            numeralSize = numeralSize,
                            thumbnailFor = thumbnailFor,
                            onEntryClick = onEntryClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveHeader(archive: MediaArchive, loading: Boolean) {
    val colors = MiraTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MiraSpacing.sm, bottom = MiraSpacing.xxxl),
    ) {
        SectionLabel("Archive")
        Spacer(Modifier.height(MiraSpacing.xs))
        Text(
            text = "MEDIA",
            style = MiraTheme.type.monthTitle,
            color = colors.ink,
        )
        when {
            loading && archive.isEmpty -> {
                Spacer(Modifier.height(MiraSpacing.sm))
                Text(
                    text = "Gathering frames…",
                    style = MiraTheme.type.uiBody,
                    color = colors.inkMuted,
                )
            }
            !archive.isEmpty -> {
                Spacer(Modifier.height(MiraSpacing.sm))
                Text(
                    text = archive.summary(),
                    style = MiraTheme.type.uiBody,
                    color = colors.inkMuted,
                )
                archive.firstDate?.let { first ->
                    Spacer(Modifier.height(MiraSpacing.xxs))
                    Text(
                        text = "SINCE ${first.displayDate()}",
                        style = MiraTheme.type.sectionLabel,
                        color = colors.inkFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(month: MediaMonth) {
    val colors = MiraTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.canvas)
            .padding(top = MiraSpacing.xxl, bottom = MiraSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("${month.month.displayName()} ${month.month.year}")
        Spacer(Modifier.weight(1f))
        Text(
            text = "${month.pieceCount} ${if (month.pieceCount == 1) "piece" else "pieces"}",
            style = MiraTheme.type.meta,
            color = colors.inkFaint,
        )
    }
}

@Composable
private fun DayCluster(
    day: MediaDay,
    gutterWidth: Dp,
    numeralSize: androidx.compose.ui.unit.TextUnit,
    thumbnailFor: suspend (Attachment) -> MediaSource?,
    onEntryClick: (Entry) -> Unit,
) {
    val colors = MiraTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MiraSpacing.xxxl),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.width(gutterWidth)) {
            Text(
                text = day.date.dayOfMonth.toString(),
                style = MiraTheme.type.dayTitle.copy(
                    fontSize = numeralSize,
                    lineHeight = numeralSize,
                ),
                color = colors.ink,
            )
            Spacer(Modifier.height(MiraSpacing.xs))
            Text(
                text = day.date.weekdayUpper().take(3),
                style = MiraTheme.type.sectionLabel,
                color = colors.inkMuted,
            )
            Text(
                text = day.date.monthKey.shortName(),
                style = MiraTheme.type.meta,
                color = colors.inkFaint,
            )
        }
        Spacer(Modifier.width(MiraSpacing.md))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TileGap),
        ) {
            DayMosaic(
                pieces = day.pieces,
                thumbnailFor = thumbnailFor,
                onEntryClick = onEntryClick,
            )
        }
    }
}

@Composable
private fun DayMosaic(
    pieces: List<MediaPiece>,
    thumbnailFor: suspend (Attachment) -> MediaSource?,
    onEntryClick: (Entry) -> Unit,
) {
    var index = 0
    while (index < pieces.size) {
        val piece = pieces[index]
        val next = pieces.getOrNull(index + 1)
        if (piece.attachment.kind == AttachmentKind.AUDIO) {
            AudioPiece(
                piece = piece,
                modifier = Modifier.fillMaxWidth(),
                thumbnailFor = thumbnailFor,
                onEntryClick = onEntryClick,
            )
            index++
        } else if (next != null && next.attachment.kind != AttachmentKind.AUDIO) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TileGap),
            ) {
                VisualPiece(
                    piece = piece,
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                    thumbnailFor = thumbnailFor,
                    onEntryClick = onEntryClick,
                )
                VisualPiece(
                    piece = next,
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                    thumbnailFor = thumbnailFor,
                    onEntryClick = onEntryClick,
                )
            }
            index += 2
        } else {
            val aspect = if (piece.attachment.kind == AttachmentKind.VIDEO) 16f / 9f else 3f / 2f
            VisualPiece(
                piece = piece,
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
                thumbnailFor = thumbnailFor,
                onEntryClick = onEntryClick,
            )
            index++
        }
    }
}

@Composable
private fun VisualPiece(
    piece: MediaPiece,
    modifier: Modifier,
    thumbnailFor: suspend (Attachment) -> MediaSource?,
    onEntryClick: (Entry) -> Unit,
) {
    val colors = MiraTheme.colors
    val attachment = piece.attachment
    val isVideo = attachment.kind == AttachmentKind.VIDEO
    var hasImage by remember(attachment.id) { mutableStateOf(false) }
    val foreground = if (hasImage) Color.White else colors.ink
    val foregroundMuted = if (hasImage) Color.White.copy(alpha = 0.82f) else colors.inkMuted

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MiraRadii.media))
            .background(colors.surfaceRaised)
            .clickable { onEntryClick(piece.entry) },
    ) {
        if (attachment.kind != AttachmentKind.AUDIO) {
            AttachmentImage(
                attachment = attachment,
                sourceFor = thumbnailFor,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                cacheKey = "media-tile",
                modifier = Modifier.matchParentSize(),
                onState = { hasImage = it == AttachmentImageState.Success },
            )
        }
        if (hasImage) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.65f),
                        ),
                    ),
            )
        } else if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .background(colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                MiraIcon(MiraIcons.Play, tint = colors.onAccent, size = 18.dp)
            }
        } else {
            MiraIcon(
                painter = MiraIcons.Photo,
                tint = colors.inkFaint,
                size = 28.dp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (isVideo && hasImage) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.42f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                MiraIcon(MiraIcons.Play, tint = Color.White, size = 18.dp)
            }
        }

        MediaInfoButton(
            attachment = attachment,
            capturedLabel = "${piece.entry.localDate.displayDate()} · ${
                formatTimestamp(piece.entry.createdAt)
            }",
            resolveLocation = { thumbnailFor(attachment)?.locationLabel() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(MiraSpacing.xs),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(MiraSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTimestamp(piece.entry.createdAt),
                style = MiraTheme.type.timestamp,
                color = foregroundMuted,
            )
            Spacer(Modifier.weight(1f))
            if (isVideo) {
                MiraIcon(
                    painter = MiraIcons.Video,
                    tint = foregroundMuted,
                    size = 14.dp,
                )
                Spacer(Modifier.width(MiraSpacing.xxs))
                Text(
                    text = formatDuration(attachment.durationMs),
                    style = MiraTheme.type.meta,
                    color = foreground,
                )
            }
            if (!attachment.isUploaded) {
                Spacer(Modifier.width(MiraSpacing.xs))
                MiraIcon(
                    painter = MiraIcons.CloudPending,
                    tint = if (hasImage) Color.White else colors.accent,
                    size = 14.dp,
                )
            }
        }
    }
}

@Composable
private fun AudioPiece(
    piece: MediaPiece,
    modifier: Modifier,
    thumbnailFor: suspend (Attachment) -> MediaSource?,
    onEntryClick: (Entry) -> Unit,
) {
    val colors = MiraTheme.colors
    val attachment = piece.attachment
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MiraRadii.card))
            .background(colors.surface)
            .clickable { onEntryClick(piece.entry) }
            .padding(horizontal = MiraSpacing.lg, vertical = MiraSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(colors.surfaceRaised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MiraIcon(MiraIcons.Microphone, tint = colors.accent, size = 18.dp)
        }
        Spacer(Modifier.width(MiraSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = piece.entry.excerpt(72).ifBlank { "Voice note" },
                style = MiraTheme.type.entryExcerpt,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(MiraSpacing.xxs))
            Text(
                text = formatTimestamp(piece.entry.createdAt),
                style = MiraTheme.type.meta,
                color = colors.inkMuted,
            )
        }
        Spacer(Modifier.width(MiraSpacing.sm))
        Text(
            text = formatDuration(attachment.durationMs),
            style = MiraTheme.type.meta,
            color = colors.inkMuted,
        )
        if (!attachment.isUploaded) {
            Spacer(Modifier.width(MiraSpacing.sm))
            MiraIcon(MiraIcons.CloudPending, tint = colors.accent, size = 14.dp)
        }
        MediaInfoButton(
            attachment = attachment,
            capturedLabel = "${piece.entry.localDate.displayDate()} · ${
                formatTimestamp(piece.entry.createdAt)
            }",
            resolveLocation = { thumbnailFor(attachment)?.locationLabel() },
            modifier = Modifier.padding(start = MiraSpacing.xs),
        )
    }
}

private fun buildArchive(entries: List<Entry>): MediaArchive {
    val pieces = entries.flatMap { entry ->
        entry.attachments.map { attachment -> MediaPiece(entry, attachment) }
    }.sortedWith(
        compareByDescending<MediaPiece> { it.entry.localDate }
            .thenByDescending { it.entry.createdAt },
    )

    val months = pieces
        .groupBy { it.entry.localDate.monthKey }
        .entries
        .sortedByDescending { it.key }
        .map { (month, monthPieces) ->
            MediaMonth(
                month = month,
                days = monthPieces
                    .groupBy { it.entry.localDate }
                    .map { (date, dayPieces) -> MediaDay(date, dayPieces) },
            )
        }

    return MediaArchive(
        months = months,
        photos = pieces.count { it.attachment.kind == AttachmentKind.IMAGE },
        videos = pieces.count { it.attachment.kind == AttachmentKind.VIDEO },
        voiceNotes = pieces.count { it.attachment.kind == AttachmentKind.AUDIO },
        firstDate = pieces.minOfOrNull { it.entry.localDate },
    )
}
