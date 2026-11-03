package com.joetr.mira.feature.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.ui.components.AttachmentDots
import com.joetr.mira.ui.components.EmptyState
import com.joetr.mira.ui.components.EntryTagLine
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.displayName
import com.joetr.mira.ui.formatTimestamp
import com.joetr.mira.ui.shortName
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme

private val TimelineDateWidth = 62.dp
private val TimelineRailWidth = 22.dp

/** Distance from the top of a row to the centre of its node. */
private val TimelineNodeCenter = 21.dp

/**
 * Every entry, newest first, grouped by month.
 *
 * A traditional date rail runs down the leading edge: the date and time sit in the gutter, a thin
 * line connects each entry's node, and today's node is filled with the accent. Month headers are
 * pinned while their entries scroll beneath them. No cards, no dividers — the rail is the only line
 * on screen, and it carries meaning rather than separating content.
 */
@Composable
public fun TimelineScreen(
    entries: List<Entry>,
    onEntryClick: (Entry) -> Unit,
    modifier: Modifier = Modifier,
    onLoadMore: () -> Unit = {},
    today: LocalDateKey? = null,
) {
    val colors = MiraTheme.colors
    val listState = rememberLazyListState()
    val grouped = remember(entries) { entries.groupBy { it.localDate.monthKey } }

    if (entries.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(colors.canvas)
                .padding(horizontal = MiraSpacing.gutter),
        ) {
            Spacer(Modifier.height(MiraSpacing.giant))
            EmptyState("Nothing written yet.")
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.canvas),
        state = listState,
        contentPadding = PaddingValues(
            start = MiraSpacing.gutter,
            end = MiraSpacing.gutter,
            top = MiraSpacing.md,
            bottom = MiraSpacing.giant,
        ),
    ) {
        grouped.forEach { (month, monthEntries) ->
            stickyHeader(key = "header-${month.value}") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.canvas)
                        .padding(top = MiraSpacing.xl, bottom = MiraSpacing.sm),
                ) {
                    SectionLabel("${month.displayName()} ${month.year}")
                }
            }
            itemsIndexed(monthEntries, key = { _, entry -> entry.id }) { index, entry ->
                TimelineRow(
                    entry = entry,
                    isFirst = index == 0,
                    isLast = index == monthEntries.lastIndex,
                    isToday = entry.localDate == today,
                    onClick = { onEntryClick(entry) },
                )
            }
        }
        item {
            Spacer(Modifier.height(MiraSpacing.xxl))
            onLoadMore()
        }
    }
}

@Composable
private fun TimelineRow(
    entry: Entry,
    isFirst: Boolean,
    isLast: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val colors = MiraTheme.colors
    val railColor = colors.inkFaint.copy(alpha = 0.45f)
    val date = entry.localDate

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .width(TimelineDateWidth)
                .padding(top = MiraSpacing.md, bottom = MiraSpacing.md),
        ) {
            Text(
                text = if (isToday) "TODAY" else date.timelineDate(),
                style = MiraTheme.type.timestamp,
                color = if (isToday) colors.accent else colors.inkMuted,
                maxLines = 1,
            )
            Spacer(Modifier.height(MiraSpacing.xxs))
            Text(
                text = formatTimestamp(entry.createdAt),
                style = MiraTheme.type.meta,
                color = colors.inkMuted,
                maxLines = 1,
            )
        }
        Canvas(
            modifier = Modifier
                .width(TimelineRailWidth)
                .fillMaxHeight(),
        ) {
            val centerX = size.width / 2f
            val nodeY = TimelineNodeCenter.toPx()
            val stroke = 1.dp.toPx()
            drawLine(
                color = railColor,
                start = Offset(centerX, if (isFirst) nodeY else 0f),
                end = Offset(centerX, if (isLast) nodeY else size.height),
                strokeWidth = stroke,
            )
            val nodeCenter = Offset(centerX, nodeY)
            val nodeRadius = 4.5.dp.toPx()
            if (isToday) {
                drawCircle(
                    color = colors.accent.copy(alpha = 0.18f),
                    radius = 8.dp.toPx(),
                    center = nodeCenter,
                )
                drawCircle(color = colors.accent, radius = nodeRadius, center = nodeCenter)
            } else {
                drawCircle(color = colors.canvas, radius = nodeRadius, center = nodeCenter)
                drawCircle(
                    color = railColor,
                    radius = nodeRadius,
                    center = nodeCenter,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(
                    start = MiraSpacing.sm,
                    top = MiraSpacing.md,
                    bottom = MiraSpacing.md,
                    end = MiraSpacing.sm,
                ),
        ) {
            Text(
                text = entry.excerpt(),
                style = MiraTheme.type.entryExcerpt,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.tags.isNotEmpty()) {
                Spacer(Modifier.height(MiraSpacing.xs))
                EntryTagLine(entry.tags)
            }
        }
        AttachmentDots(
            entry,
            modifier = Modifier.padding(top = TimelineNodeCenter - MiraSpacing.xs),
        )
    }
}

/** `30 OCT`, matching the month already named by the pinned header. */
private fun LocalDateKey.timelineDate(): String =
    "${dayOfMonth.toString().padStart(2, '0')} ${monthKey.shortName()}"
