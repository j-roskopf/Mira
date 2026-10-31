package com.joetr.mira.feature.calendar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.domain.lengthOfMonth
import com.joetr.mira.ui.WEEKDAY_HEADS
import com.joetr.mira.ui.components.CircleIconButton
import com.joetr.mira.ui.components.HeatmapDay
import com.joetr.mira.ui.displayName
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.monthShortName
import com.joetr.mira.ui.sundayFirstColumn
import com.joetr.mira.ui.motion.sharedDayCircle
import com.joetr.mira.ui.theme.MiraMotion
import com.joetr.mira.ui.theme.MiraSizes
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme
import com.joetr.mira.ui.theme.busyThreshold
import com.joetr.mira.ui.theme.heatmapStep

/**
 * The home screen: a month of circles whose colour is how much you wrote.
 *
 * A day with no entries draws no circle at all, only a faint numeral — so a month you journalled
 * reads as a constellation and a month you did not reads as empty. That contrast is the whole
 * point of the screen.
 */
@Composable
public fun CalendarScreen(
    month: MonthKey,
    today: LocalDateKey,
    activity: Map<LocalDateKey, Int>,
    selected: LocalDateKey?,
    onMonthChange: (MonthKey) -> Unit,
    onDayClick: (LocalDateKey) -> Unit,
    onYearClick: (Int) -> Unit,
    onSettingsClick: () -> Unit,
    onComposeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors
    val threshold = remember(activity) { busyThreshold(activity.values) }

    Box(modifier.fillMaxSize().background(colors.canvas)) {
        Column(Modifier.fillMaxSize().padding(horizontal = MiraSpacing.gutter)) {
            Spacer(Modifier.height(MiraSpacing.lg))

            YearNavigator(
                month = month,
                onMonthSelect = { onMonthChange(MonthKey.of(month.year, it)) },
                onYearClick = { onYearClick(month.year) },
                onYearStep = { onMonthChange(month.plusMonths(it * 12)) },
                onSettingsClick = onSettingsClick,
            )

            Spacer(Modifier.height(MiraSpacing.xxxl))

            Text(
                text = month.displayName().uppercase(),
                style = MiraTheme.type.monthTitle,
                color = colors.ink,
            )

            Spacer(Modifier.height(MiraSpacing.xl))

            WeekdayHeader()
            Spacer(Modifier.height(MiraSpacing.sm))

            MonthGrid(
                month = month,
                today = today,
                selected = selected,
                activity = activity,
                busyThreshold = threshold,
                onDayClick = onDayClick,
            )
        }

        CircleIconButton(
            icon = MiraIcons.Plus,
            contentDescription = "New entry",
            onClick = onComposeClick,
            filled = true,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(MiraSpacing.xxl),
        )
    }
}

/**
 * Year and month strip — the entire navigator.
 *
 * Tap a month to jump, tap the year to open the year view. No arrows, no dropdown; Timepage's
 * compact strip does the whole job in two rows.
 */
@Composable
private fun YearNavigator(
    month: MonthKey,
    onMonthSelect: (Int) -> Unit,
    onYearClick: () -> Unit,
    onYearStep: (Int) -> Unit,
    onSettingsClick: () -> Unit,
) {
    val colors = MiraTheme.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = month.year.toString(),
                style = MiraTheme.type.yearNav,
                color = colors.inkMuted,
                modifier = Modifier.clickable(onClick = onYearClick),
            )
            Spacer(Modifier.width(MiraSpacing.sm))
            Box(
                Modifier
                    .size(4.dp)
                    .background(colors.accent, androidx.compose.foundation.shape.CircleShape)
                    .clickable { onYearStep(1) },
            )
            Spacer(Modifier.weight(1f))
            CircleIconButton(
                icon = MiraIcons.Settings,
                contentDescription = "Settings",
                onClick = onSettingsClick,
            )
        }

        Spacer(Modifier.height(MiraSpacing.sm))

        listOf(1..6, 7..12).forEach { range ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                range.forEach { number ->
                    val isCurrent = number == month.monthNumber
                    Text(
                        text = monthShortName(number),
                        style = MiraTheme.type.yearNav,
                        color = if (isCurrent) colors.ink else colors.inkFaint,
                        modifier = Modifier
                            .clickable { onMonthSelect(number) }
                            .padding(vertical = MiraSpacing.xs, horizontal = MiraSpacing.xs),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth()) {
        WEEKDAY_HEADS.forEach { head ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = head,
                    style = MiraTheme.type.weekdayHead,
                    color = MiraTheme.colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: MonthKey,
    today: LocalDateKey,
    selected: LocalDateKey?,
    activity: Map<LocalDateKey, Int>,
    busyThreshold: Int,
    onDayClick: (LocalDateKey) -> Unit,
) {
    val daysInMonth = lengthOfMonth(month.year, month.monthNumber)
    val leadingBlanks = remember(month) { month.firstDay.sundayFirstColumn() }
    val cells = leadingBlanks + daysInMonth
    val rows = (cells + 6) / 7

    // Staggered first paint, once per month rather than on every recomposition.
    var revealed by remember(month) { mutableStateOf(false) }
    LaunchedEffect(month) { revealed = true }

    Column(verticalArrangement = Arrangement.spacedBy(MiraSizes.dayGap)) {
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val index = row * 7 + column
                    val dayNumber = index - leadingBlanks + 1
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..daysInMonth) {
                            val date = LocalDateKey(
                                "${month.value}-${dayNumber.toString().padStart(2, '0')}",
                            )
                            val count = activity[date] ?: 0
                            val alpha by animateFloatAsState(
                                targetValue = if (revealed || !MiraTheme.motionEnabled) 1f else 0f,
                                animationSpec = tween(
                                    durationMillis = MiraMotion.STANDARD_MS,
                                    delayMillis = index * MiraMotion.HEATMAP_STAGGER_MS,
                                ),
                                label = "heatmap-reveal",
                            )
                            HeatmapDay(
                                day = dayNumber,
                                rampStep = heatmapStep(count, busyThreshold),
                                isToday = date == today,
                                isSelected = date == selected,
                                onClick = { onDayClick(date) },
                                modifier = Modifier
                                    .alpha(alpha)
                                    .sharedDayCircle(date.value),
                            )
                        }
                    }
                }
            }
        }
    }
}
