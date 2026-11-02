package com.joetr.mira.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.domain.lengthOfMonth
import com.joetr.mira.ui.WEEKDAY_HEADS
import com.joetr.mira.ui.components.CircleIconButton
import com.joetr.mira.ui.components.HeatmapDay
import com.joetr.mira.ui.components.MiraFab
import com.joetr.mira.ui.displayName
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.monthShortName
import com.joetr.mira.ui.sundayFirstColumn
import com.joetr.mira.ui.motion.sharedDayCircle
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSizes
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraAccentCanvas
import com.joetr.mira.ui.theme.MiraTheme
import com.joetr.mira.ui.theme.busyThreshold
import com.joetr.mira.ui.theme.heatmapStep
import kotlinx.coroutines.flow.collect

private const val PAGER_CENTER: Int = Int.MAX_VALUE / 2

private fun MonthKey.monthsUntil(other: MonthKey): Int =
    (other.year * 12 + other.monthNumber) - (year * 12 + monthNumber)

private fun rowsIn(month: MonthKey): Int =
    (month.firstDay.sundayFirstColumn() + lengthOfMonth(month.year, month.monthNumber) + 6) / 7

/**
 * Row budget for the pager while the gesture is in flight. The settled month and the committed
 * [month] bracket every page that can come into view, so the height only changes after a settle —
 * never mid-drag.
 */
private fun rowsAcross(from: MonthKey, to: MonthKey): Int {
    val first = minOf(from, to).minusMonths(1)
    val last = maxOf(from, to).plusMonths(1)
    val distance = first.monthsUntil(last)
    if (distance > 24) return 6
    return (0..distance).maxOf { rowsIn(first.plusMonths(it)) }
}

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
    showComposeButton: Boolean = true,
) {
    MiraAccentCanvas {
        val colors = MiraTheme.colors
        val threshold = remember(activity) { busyThreshold(activity.values) }

        BoxWithConstraints(modifier.fillMaxSize().background(colors.canvas)) {
            val expanded = maxWidth >= 800.dp
            val availableHeight = maxHeight
            val pagerState = rememberPagerState(
                initialPage = PAGER_CENTER,
                pageCount = { Int.MAX_VALUE },
            )
            val anchorMonth = remember { month }
            val pageToMonth: (Int) -> MonthKey = remember(anchorMonth) {
                { page -> anchorMonth.plusMonths(page - PAGER_CENTER) }
            }
            val monthToPage: (MonthKey) -> Int = remember(anchorMonth) {
                { value -> PAGER_CENTER + anchorMonth.monthsUntil(value) }
            }
            val latestMonth by rememberUpdatedState(month)
            val latestOnMonthChange by rememberUpdatedState(onMonthChange)

            LaunchedEffect(month) {
                val target = monthToPage(month)
                if (pagerState.currentPage != target || pagerState.currentPageOffsetFraction != 0f) {
                    withFrameNanos { }
                    pagerState.animateScrollToPage(target)
                }
            }

            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    val target = pageToMonth(page)
                    if (target != latestMonth) latestOnMonthChange(target)
                }
            }

            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MiraSpacing.gutter),
            ) {
                Spacer(Modifier.height(MiraSpacing.lg))

                YearNavigator(
                    month = month,
                    onMonthSelect = { onMonthChange(MonthKey.of(month.year, it)) },
                    onYearClick = { onYearClick(month.year) },
                    onYearStep = { onMonthChange(month.plusMonths(it * 12)) },
                    onSettingsClick = onSettingsClick,
                )

                Spacer(Modifier.height(MiraSpacing.xxxl))

                val settledPage = pagerState.settledPage
                val settledMonth = pageToMonth(settledPage)
                val visibleRows = remember(settledMonth, month) {
                    rowsAcross(settledMonth, month)
                }
                val rowHeight = if (expanded) {
                    ((availableHeight - 304.dp) / visibleRows).coerceIn(64.dp, 120.dp)
                } else MiraSizes.touchTarget
                val density = LocalDensity.current
                val chromeHeight = with(density) {
                    MiraTheme.type.monthTitle.lineHeight.toDp() + MiraSpacing.xl +
                        MiraTheme.type.weekdayHead.lineHeight.toDp() + MiraSpacing.sm
                }
                val pageHeight = chromeHeight + rowHeight * visibleRows +
                    MiraSizes.dayGap * (visibleRows - 1)

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().height(pageHeight),
                    key = { it },
                ) { page ->
                    val pageMonth = pageToMonth(page)
                    Column(Modifier.fillMaxWidth().height(pageHeight)) {
                        Text(
                            text = pageMonth.displayName().uppercase(),
                            style = MiraTheme.type.monthTitle,
                            color = colors.ink,
                        )

                        Spacer(Modifier.height(MiraSpacing.xl))

                        WeekdayHeader()
                        Spacer(Modifier.height(MiraSpacing.sm))

                        MonthGrid(
                            month = pageMonth,
                            today = today,
                            selected = selected,
                            activity = activity,
                            busyThreshold = threshold,
                            onDayClick = onDayClick,
                            rowHeight = rowHeight,
                            daySize = if (expanded) 56.dp else MiraSizes.dayCircle,
                            enableSharedTransition = page == pagerState.settledPage,
                        )
                    }
                }
                Spacer(Modifier.height(104.dp))
            }

            if (showComposeButton) MiraFab(
                icon = MiraIcons.Plus,
                contentDescription = "New entry",
                onClick = onComposeClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(MiraSpacing.xxl),
            )
        }
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onMonthSelect(number) }
                            .padding(vertical = MiraSpacing.xs, horizontal = MiraSpacing.xs),
                    ) {
                        Text(
                            text = monthShortName(number),
                            style = MiraTheme.type.yearNav,
                            color = if (isCurrent) colors.ink else colors.inkFaint,
                        )
                        Spacer(Modifier.height(MiraSpacing.xs))
                        Box(
                            Modifier
                                .height(2.dp)
                                .width(if (isCurrent) 16.dp else 0.dp)
                                .background(colors.accent, RoundedCornerShape(MiraRadii.pill)),
                        )
                    }
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
    rowHeight: Dp,
    daySize: Dp,
    enableSharedTransition: Boolean,
) {
    val daysInMonth = lengthOfMonth(month.year, month.monthNumber)
    val leadingBlanks = remember(month) { month.firstDay.sundayFirstColumn() }
    val cells = leadingBlanks + daysInMonth
    val rows = (cells + 6) / 7

    Column(verticalArrangement = Arrangement.spacedBy(MiraSizes.dayGap)) {
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth().height(rowHeight)) {
                repeat(7) { column ->
                    val index = row * 7 + column
                    val dayNumber = index - leadingBlanks + 1
                    Box(Modifier.weight(1f).height(rowHeight), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..daysInMonth) {
                            val date = LocalDateKey(
                                "${month.value}-${dayNumber.toString().padStart(2, '0')}",
                            )
                            val count = activity[date] ?: 0
                            HeatmapDay(
                                day = dayNumber,
                                circleSize = daySize,
                                rampStep = heatmapStep(count, busyThreshold),
                                isToday = date == today,
                                isSelected = date == selected,
                                onClick = { onDayClick(date) },
                                modifier = if (enableSharedTransition) {
                                    Modifier.sharedDayCircle(date.value)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
