package com.joetr.mira.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.joetr.mira.domain.DayActivity
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.domain.lengthOfMonth
import com.joetr.mira.ui.components.CircleIconButton
import com.joetr.mira.ui.displayName
import com.joetr.mira.ui.icons.MiraIcons
import androidx.compose.ui.unit.dp
import com.joetr.mira.ui.sundayFirstColumn
import com.joetr.mira.ui.theme.MiraSizes
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraAccentCanvas
import com.joetr.mira.ui.theme.MiraTheme
import com.joetr.mira.ui.theme.busyThreshold
import com.joetr.mira.ui.theme.heatmapStep

/**
 * A whole year as twelve mini heatmaps.
 *
 * Purely ornamental, and deliberately so — it is the artefact of a year of journalling, and it
 * should be the best-looking thing in the app.
 */
@Composable
public fun YearScreen(
    year: Int,
    activity: List<DayActivity>,
    onMonthClick: (MonthKey) -> Unit,
    onBack: () -> Unit,
    onYearChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    MiraAccentCanvas {
        val colors = MiraTheme.colors
        val byDate = remember(activity) { activity.associate { it.date to it.entryCount } }
        val threshold = remember(activity) { busyThreshold(activity.map { it.entryCount }) }
        val totalEntries = remember(activity) { activity.sumOf { it.entryCount } }
        val daysWritten = remember(activity) { activity.count { it.entryCount > 0 } }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(colors.canvas)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MiraSpacing.gutter),
        ) {
            Spacer(Modifier.height(MiraSpacing.lg))

            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(MiraIcons.Back, "Back", onClick = onBack)
                Spacer(Modifier.weight(1f))
                Text(
                    text = (year - 1).toString(),
                    style = MiraTheme.type.yearNav,
                    color = colors.inkFaint,
                    modifier = Modifier
                        .clickable { onYearChange(year - 1) }
                        .padding(MiraSpacing.sm),
                )
                Text(
                    text = year.toString(),
                    style = MiraTheme.type.monthTitle,
                    color = colors.ink,
                    modifier = Modifier.padding(horizontal = MiraSpacing.md),
                )
                Text(
                    text = (year + 1).toString(),
                    style = MiraTheme.type.yearNav,
                    color = colors.inkFaint,
                    modifier = Modifier
                        .clickable { onYearChange(year + 1) }
                        .padding(MiraSpacing.sm),
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(MiraSizes.touchTarget))
            }

            Spacer(Modifier.height(MiraSpacing.sm))

            Text(
                text = "$daysWritten DAYS · $totalEntries ENTRIES",
                style = MiraTheme.type.dayDate,
                color = colors.inkMuted,
            )

            Spacer(Modifier.height(MiraSpacing.sectionGap))

            (1..12).chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MiraSpacing.lg),
                ) {
                    row.forEach { monthNumber ->
                        val month = MonthKey.of(year, monthNumber)
                        Box(Modifier.weight(1f)) {
                            MiniMonth(
                                month = month,
                                counts = byDate,
                                busyThreshold = threshold,
                                onClick = { onMonthClick(month) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(MiraSpacing.xxl))
            }

            Spacer(Modifier.height(MiraSpacing.giant))
        }
    }
}

@Composable
private fun MiniMonth(
    month: MonthKey,
    counts: Map<LocalDateKey, Int>,
    busyThreshold: Int,
    onClick: () -> Unit,
) {
    val colors = MiraTheme.colors
    val daysInMonth = lengthOfMonth(month.year, month.monthNumber)
    val leading = remember(month) { month.firstDay.sundayFirstColumn() }
    val rows = (leading + daysInMonth + 6) / 7

    Column(Modifier.clickable(onClick = onClick)) {
        Text(
            text = month.displayName().take(3).uppercase(),
            style = MiraTheme.type.weekdayHead,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(MiraSpacing.sm))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(rows) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(7) { column ->
                        val index = row * 7 + column
                        val day = index - leading + 1
                        if (day in 1..daysInMonth) {
                            val date = LocalDateKey(
                                "${month.value}-${day.toString().padStart(2, '0')}",
                            )
                            val step = heatmapStep(counts[date] ?: 0, busyThreshold)
                            Box(
                                Modifier
                                    .size(MiraSizes.yearDot)
                                    .background(
                                        color = colors.heatmap.getOrNull(step) ?: colors.surface,
                                        shape = CircleShape,
                                    ),
                            )
                        } else {
                            Box(Modifier.size(MiraSizes.yearDot))
                        }
                    }
                }
            }
        }
    }
}
