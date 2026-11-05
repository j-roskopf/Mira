package com.joetr.mira.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.domain.lengthOfMonth
import com.joetr.mira.media.MediaSource
import com.joetr.mira.ui.WEEKDAY_HEADS
import com.joetr.mira.ui.components.MiraFab
import com.joetr.mira.ui.displayName
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.monthName
import com.joetr.mira.ui.monthShortName
import com.joetr.mira.ui.sundayFirstColumn
import com.joetr.mira.ui.motion.sharedDayCircle
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSizes
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraAccentCanvas
import com.joetr.mira.ui.theme.MiraTheme
import com.joetr.mira.ui.theme.busyThreshold
import kotlinx.coroutines.flow.collect

private const val PAGER_CENTER: Int = Int.MAX_VALUE / 2

/** The desktop month rail, sitting between the app sidebar and the grid. */
private val DesktopRailWidth: Dp = 200.dp

/** Gutter between day tiles — tighter than the general day gap so squares nearly touch. */
private val CalendarTileGap: Dp = 4.dp

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
 * The home screen: a month of numerals, with a small pip per entry beneath each written day.
 *
 * A day with no entries keeps only a faint numeral — so a month you journalled reads as a
 * constellation and a month you did not reads as empty. That contrast is the whole point of the
 * screen.
 *
 * On a wide canvas the year-and-month navigator moves out of the page header and into a rail on
 * the left, letting the grid own the full height and width. The grid scales its numerals and pips
 * to the space it is given instead of staying pinned to phone sizes.
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
    onComposeClick: () -> Unit,
    modifier: Modifier = Modifier,
    showComposeButton: Boolean = true,
    dayMedia: Map<LocalDateKey, CalendarDayMedia> = emptyMap(),
    thumbnailFor: suspend (Attachment) -> MediaSource? = { null },
) {
    MiraAccentCanvas {
        val colors = MiraTheme.colors
        val threshold = remember(activity) { busyThreshold(activity.values) }

        BoxWithConstraints(modifier.fillMaxSize().background(colors.canvas)) {
            val expanded = maxWidth >= 800.dp
            val windowWidth = maxWidth
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

            if (expanded) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = MiraSpacing.gutterWide),
                ) {
                    DesktopMonthRail(
                        month = month,
                        onMonthSelect = { onMonthChange(MonthKey.of(month.year, it)) },
                        onYearClick = { onYearClick(month.year) },
                        onYearStep = { onMonthChange(month.plusMonths(it * 12)) },
                        modifier = Modifier.width(DesktopRailWidth).fillMaxHeight(),
                    )
                    Spacer(Modifier.width(MiraSpacing.huge))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(vertical = MiraSpacing.xxxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        DesktopMonthPager(
                            pagerState = pagerState,
                            pageToMonth = pageToMonth,
                            month = month,
                            today = today,
                            selected = selected,
                            activity = activity,
                            dayMedia = dayMedia,
                            busyThreshold = threshold,
                            thumbnailFor = thumbnailFor,
                            onDayClick = onDayClick,
                        )
                    }
                }
            } else {
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
                    )

                    Spacer(Modifier.height(MiraSpacing.xxxl))

                    val settledPage = pagerState.settledPage
                    val settledMonth = pageToMonth(settledPage)
                    val visibleRows = remember(settledMonth, month) {
                        rowsAcross(settledMonth, month)
                    }
                    // Rows track the day tiles instead of stretching to fill the window. Leftover
                    // height would otherwise turn into a large vertical gap between squares.
                    val rowHeight = (windowWidth - MiraSpacing.gutter * 2) / 7 +
                        CalendarTileGap
                    val density = LocalDensity.current
                    val chromeHeight = with(density) {
                        MiraTheme.type.monthTitle.lineHeight.toDp() + MiraSpacing.xl +
                            MiraTheme.type.weekdayHead.lineHeight.toDp() + MiraSpacing.sm
                    }
                    val pageHeight = chromeHeight + rowHeight * visibleRows +
                        CalendarTileGap * (visibleRows - 1)

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
                                dayMedia = dayMedia,
                                busyThreshold = threshold,
                                thumbnailFor = thumbnailFor,
                                onDayClick = onDayClick,
                                rowHeight = rowHeight,
                                numeralStyle = MiraTheme.type.dayNumeral,
                                enableSharedTransition = page == pagerState.settledPage,
                            )
                        }
                    }
                    Spacer(Modifier.height(104.dp))
                }
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
 * The desktop navigator: the year at the top, then every month as a vertical list. The current
 * month is a filled pill, mirroring the reference's month column rather than a header above the
 * grid.
 */
@Composable
private fun DesktopMonthRail(
    month: MonthKey,
    onMonthSelect: (Int) -> Unit,
    onYearClick: () -> Unit,
    onYearStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors
    val monthStyle = MiraTheme.type.uiBody.copy(
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
    )
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = month.year.toString(),
                style = MiraTheme.type.monthTitle,
                color = colors.inkMuted,
                modifier = Modifier.clickable(onClick = onYearClick),
            )
            Spacer(Modifier.width(MiraSpacing.sm))
            Box(
                Modifier
                    .size(5.dp)
                    .background(colors.accent, CircleShape)
                    .clickable { onYearStep(1) },
            )
        }

        Spacer(Modifier.height(MiraSpacing.xxxl))

        // Spread the twelve months down the whole rail rather than letting them bunch at the top.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (number in 1..12) {
                val isCurrent = number == month.monthNumber
                Box(
                    Modifier
                        .wrapContentWidth()
                        .clip(RoundedCornerShape(MiraRadii.pill))
                        .background(if (isCurrent) colors.ink else Color.Transparent)
                        .clickable { onMonthSelect(number) }
                        .padding(horizontal = MiraSpacing.sm, vertical = MiraSpacing.sm),
                ) {
                    Text(
                        text = monthName(number).uppercase(),
                        style = monthStyle,
                        color = if (isCurrent) colors.canvas else colors.inkMuted,
                    )
                }
            }
        }
    }
}

/**
 * The wide-canvas grid. Unlike the compact pager it carries no month title — the rail already
 * names the month — so every vertical pixel goes to the days.
 *
 * The month reads as a constellation: written days carry a row of pips, while unwritten days are
 * bare numerals. The pip count is the day's entry count, which gives the grid a shape without
 * turning every day into a filled disc.
 */
@Composable
private fun DesktopMonthPager(
    pagerState: PagerState,
    pageToMonth: (Int) -> MonthKey,
    month: MonthKey,
    today: LocalDateKey,
    selected: LocalDateKey?,
    activity: Map<LocalDateKey, Int>,
    dayMedia: Map<LocalDateKey, CalendarDayMedia>,
    busyThreshold: Int,
    thumbnailFor: suspend (Attachment) -> MediaSource?,
    onDayClick: (LocalDateKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val weekdayStyle = MiraTheme.type.weekdayHead.copy(
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp,
    )
    BoxWithConstraints(modifier) {
        val settledMonth = pageToMonth(pagerState.settledPage)
        val visibleRows = remember(settledMonth, month) { rowsAcross(settledMonth, month) }
        val density = LocalDensity.current
        val gap = CalendarTileGap
        val headerHeight = with(density) { weekdayStyle.lineHeight.toDp() } + MiraSpacing.xl
        val cellWidth = maxWidth / 7
        val rowHeightFromHeight =
            (maxHeight - headerHeight - gap * (visibleRows - 1)) / visibleRows
        val rowHeight = minOf(rowHeightFromHeight, cellWidth).coerceAtLeast(MiraSizes.touchTarget)
        val pageHeight = headerHeight + rowHeight * visibleRows + gap * (visibleRows - 1)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(pageHeight),
            key = { it },
        ) { page ->
            val pageMonth = pageToMonth(page)
            Column(Modifier.fillMaxWidth().height(pageHeight)) {
                WeekdayHeader(style = weekdayStyle)
                Spacer(Modifier.height(MiraSpacing.xl))
                MonthGrid(
                    month = pageMonth,
                    today = today,
                    selected = selected,
                    activity = activity,
                    dayMedia = dayMedia,
                    busyThreshold = busyThreshold,
                    thumbnailFor = thumbnailFor,
                    onDayClick = onDayClick,
                    rowHeight = rowHeight,
                    numeralStyle = MiraTheme.type.dayNumeral,
                    activitySized = true,
                    enableSharedTransition = page == pagerState.settledPage,
                )
            }
        }
    }
}

/**
 * Year and month strip — the entire navigator on compact screens.
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
                    .background(colors.accent, CircleShape)
                    .clickable { onYearStep(1) },
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
private fun WeekdayHeader(style: TextStyle = MiraTheme.type.weekdayHead) {
    Row(Modifier.fillMaxWidth()) {
        WEEKDAY_HEADS.forEach { head ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = head,
                    style = style,
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
    dayMedia: Map<LocalDateKey, CalendarDayMedia>,
    busyThreshold: Int,
    thumbnailFor: suspend (Attachment) -> MediaSource?,
    onDayClick: (LocalDateKey) -> Unit,
    rowHeight: Dp,
    numeralStyle: TextStyle,
    activitySized: Boolean = false,
    enableSharedTransition: Boolean,
) {
    val daysInMonth = lengthOfMonth(month.year, month.monthNumber)
    val leadingBlanks = remember(month) { month.firstDay.sundayFirstColumn() }
    val cells = leadingBlanks + daysInMonth
    val rows = (cells + 6) / 7

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Days fill their column rather than sitting as small discs inside it, so a month with a
        // photo every day reads as one continuous strip with a thin gutter.
        val cellWidth = maxWidth / 7
        val tileSize = (minOf(cellWidth, rowHeight) - CalendarTileGap)
            .coerceAtLeast(MiraSizes.touchTarget)

        Column(verticalArrangement = Arrangement.spacedBy(CalendarTileGap)) {
            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth().height(rowHeight)) {
                    repeat(7) { column ->
                        val index = row * 7 + column
                        val dayNumber = index - leadingBlanks + 1
                        Box(
                            Modifier.weight(1f).height(rowHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (dayNumber in 1..daysInMonth) {
                                val date = LocalDateKey(
                                    "${month.value}-${dayNumber.toString().padStart(2, '0')}",
                                )
                                val count = activity[date] ?: 0
                                val isMarked = date == today || date == selected
                                // The tile itself carries activity now, so every numeral keeps the
                                // same size; today and the selection get a slightly larger one.
                                val style = if (activitySized) {
                                    val factor = if (isMarked) 0.30f else 0.24f
                                    numeralStyle.copy(
                                        fontSize = (tileSize.value * factor).sp,
                                        lineHeight = (tileSize.value * factor).sp,
                                    )
                                } else {
                                    numeralStyle
                                }
                                CalendarDayCell(
                                    day = dayNumber,
                                    entryCount = count,
                                    media = dayMedia[date],
                                    busyThreshold = busyThreshold,
                                    isToday = date == today,
                                    isSelected = date == selected,
                                    tileSize = tileSize,
                                    numeralStyle = style,
                                    thumbnailFor = thumbnailFor,
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
}
