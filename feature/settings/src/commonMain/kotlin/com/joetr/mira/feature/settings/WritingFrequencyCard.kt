package com.joetr.mira.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.DayActivity
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.ui.components.MiraCard
import com.joetr.mira.ui.icons.MiraIcon
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.monthShortName
import com.joetr.mira.ui.sundayFirstColumn
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme
import com.joetr.mira.ui.theme.busyThreshold
import com.joetr.mira.ui.theme.heatmapStep
import kotlin.math.roundToInt
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** A year of weeks, scrollable on narrow cards and fully visible on wide ones. */
private const val VISIBLE_WEEKS: Int = 52

/** Enough history that an ongoing run is not clipped at the visible window. */
private const val WRITING_HISTORY_WEEKS: Int = 260

/** The Sunday that starts the history needed to measure the current run. */
public fun writingHistoryStart(today: LocalDateKey): LocalDateKey =
    writingWindowStart(today, WRITING_HISTORY_WEEKS)

/** The Sunday that starts the [weeks]-week window ending on [today]. */
internal fun writingWindowStart(today: LocalDateKey, weeks: Int): LocalDateKey =
    today.minusDays(today.sundayFirstColumn() + (weeks - 1) * 7)

/**
 * A compact contribution graph of days written, for Settings.
 *
 * Columns are weeks, rows are days of the week, and each square is coloured from the accent's
 * heatmap ramp. The header counts written days in the window, while the footer names the current
 * run — the streak ending today (or yesterday, while today is still unwritten). Tapping opens the
 * year view, where the same data is the whole point of the screen.
 */
@Composable
internal fun WritingFrequencyCard(
    activity: List<DayActivity>,
    today: LocalDateKey,
    onOpenYear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors
    val density = LocalDensity.current
    val counts = remember(activity) { activity.associate { it.date to it.entryCount } }
    val run = remember(counts, today) { currentRun(counts, today) }

    MiraCard(modifier = modifier, onClick = onOpenYear) {
        BoxWithConstraints {
            val weeks = VISIBLE_WEEKS
            val start = remember(today, weeks) { writingWindowStart(today, weeks) }
            val labels = remember(start, weeks) { monthLabels(start, weeks) }
            val window = remember(activity, start, today) {
                activity.filter { it.date >= start && it.date <= today }
            }
            val daysWritten = remember(window) { window.count { it.entryCount > 0 } }
            val threshold = remember(window) { busyThreshold(window.map { it.entryCount }) }
            val gap = 3.dp
            // Whole-pixel cells keep the columns from rounding past the card edge. On phones a
            // full year is wider than the card, so the graph scrolls rather than shrinking to mush.
            val gapPx = with(density) { gap.toPx() }.roundToInt()
            val availablePx = with(density) { maxWidth.toPx() }.roundToInt()
            val minCellPx = with(density) { 8.dp.toPx() }.roundToInt()
            val maxCellPx = with(density) { 14.dp.toPx() }.roundToInt()
            val cellPx = ((availablePx - gapPx * (weeks - 1)) / weeks)
                .coerceIn(minCellPx, maxCellPx)
            val cell = with(density) { cellPx.toDp() }
            val gridWidth = cell * weeks.toFloat() + gap * (weeks - 1).toFloat()
            val scrollState = rememberScrollState()
            val canScrollBackward by remember {
                derivedStateOf { scrollState.value > 0 }
            }
            val canScrollForward by remember {
                derivedStateOf { scrollState.value < scrollState.maxValue }
            }

            // Open on the most recent weeks; the rest of the year is a leftward swipe away.
            LaunchedEffect(scrollState.maxValue) {
                if (scrollState.maxValue > 0) scrollState.scrollTo(scrollState.maxValue)
            }

            Column {
                Text(
                    text = when (daysWritten) {
                        0 -> "No days yet."
                        1 -> "1 day so far."
                        else -> "$daysWritten days so far."
                    },
                    style = MiraTheme.type.dayTitle,
                    color = colors.ink,
                )
                Spacer(Modifier.height(MiraSpacing.md))

                Box(
                    modifier = Modifier.drawWithContent {
                        drawContent()
                        val fade = 20.dp.toPx()
                        if (scrollState.value > 0) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(colors.surface, Color.Transparent),
                                    startX = 0f,
                                    endX = fade,
                                ),
                            )
                        }
                        if (scrollState.value < scrollState.maxValue) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, colors.surface),
                                    startX = size.width - fade,
                                    endX = size.width,
                                ),
                            )
                        }
                    },
                ) {
                    Column(Modifier.horizontalScroll(scrollState)) {
                        MonthLabelsRow(
                            labels = labels,
                            cell = cell,
                            gap = gap,
                            gridWidth = gridWidth,
                        )
                        Spacer(Modifier.height(MiraSpacing.xs))
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            repeat(weeks) { week ->
                                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                    repeat(7) { day ->
                                        val date = start.plusDays(week * 7 + day)
                                        val count = counts[date] ?: 0
                                        val color = when {
                                            date > today -> Color.Transparent
                                            count <= 0 -> colors.surfaceRaised
                                            else -> colors.heatmap.getOrElse(
                                                heatmapStep(count, threshold),
                                            ) { colors.heatmap.last() }
                                        }
                                        Box(
                                            Modifier
                                                .size(cell)
                                                .background(color, RoundedCornerShape(2.dp)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (canScrollBackward) {
                        MiraIcon(
                            painter = MiraIcons.Back,
                            tint = colors.inkMuted,
                            size = 14.dp,
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                    }
                    if (canScrollForward) {
                        MiraIcon(
                            painter = MiraIcons.Chevron,
                            tint = colors.inkMuted,
                            size = 14.dp,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
                Spacer(Modifier.height(MiraSpacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (run > 0) "Day $run of this run." else "Start a new run today.",
                        style = MiraTheme.type.meta,
                        color = colors.inkMuted,
                        modifier = Modifier.weight(1f),
                    )
                    MiraIcon(
                        painter = MiraIcons.Chevron,
                        tint = colors.inkFaint,
                        size = 16.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthLabelsRow(labels: List<MonthLabel>, cell: Dp, gap: Dp, gridWidth: Dp) {
    val density = LocalDensity.current
    val cellPx = with(density) { cell.toPx() }
    val gapPx = with(density) { gap.toPx() }
    val step = cellPx + gapPx
    val gridWidthPx = with(density) { gridWidth.toPx() }.roundToInt()

    Layout(
        content = {
            labels.forEach { label ->
                Text(
                    text = label.text,
                    style = MiraTheme.type.weekdayHead,
                    color = MiraTheme.colors.inkMuted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        },
    ) { measurables, _ ->
        val placeables = measurables.map { it.measure(Constraints(maxWidth = gridWidthPx)) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(gridWidthPx, height) {
            placeables.forEachIndexed { index, placeable ->
                val x = (labels[index].startWeek * step).roundToInt()
                    .coerceAtMost((gridWidthPx - placeable.width).coerceAtLeast(0))
                placeable.place(x, 0)
            }
        }
    }
}

private data class MonthLabel(
    val text: String,
    val startWeek: Int,
)

/** Labels the week containing each month's first day, plus a label for the starting month. */
private fun monthLabels(start: LocalDateKey, weeks: Int): List<MonthLabel> {
    val firsts = mutableListOf<Pair<Int, Int>>()
    repeat(weeks) { week ->
        val weekStart = start.plusDays(week * 7)
        for (offset in 0..6) {
            val date = weekStart.plusDays(offset)
            if (date.dayOfMonth == 1) {
                firsts.add(week to date.monthNumber)
                break
            }
        }
    }
    // A rolling year can start mid-month and end on the same month a year later. Label only the
    // real first-of-month occurrence then, rather than showing the same month at both ends.
    val startsMidMonth = firsts.none { it.first == 0 }
    if (startsMidMonth && start.monthNumber != firsts.lastOrNull()?.second) {
        firsts.add(0, 0 to start.monthNumber)
    }
    return firsts.map { (week, month) ->
        MonthLabel(text = monthShortName(month), startWeek = week)
    }
}

/**
 * Entries in the run ending today. Today may still be unwritten, so the run is measured from
 * yesterday until the first entry of the day appears.
 */
private fun currentRun(counts: Map<LocalDateKey, Int>, today: LocalDateKey): Int {
    var cursor = if ((counts[today] ?: 0) > 0) today else today.minusDays(1)
    var run = 0
    while ((counts[cursor] ?: 0) > 0) {
        run++
        cursor = cursor.minusDays(1)
    }
    return run
}

private fun LocalDateKey.plusDays(days: Int): LocalDateKey =
    LocalDateKey.of(toLocalDate().plus(days, DateTimeUnit.DAY))

private fun LocalDateKey.minusDays(days: Int): LocalDateKey =
    LocalDateKey.of(toLocalDate().minus(days, DateTimeUnit.DAY))
