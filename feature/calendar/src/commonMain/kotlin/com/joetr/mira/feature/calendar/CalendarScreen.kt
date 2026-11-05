package com.joetr.mira.feature.calendar

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.domain.lengthOfMonth
import com.joetr.mira.media.MediaSource
import com.joetr.mira.ui.WEEKDAY_HEADS
import com.joetr.mira.ui.components.MiraFab
import com.joetr.mira.ui.displayName
import com.joetr.mira.ui.formatTimestamp
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.monthName
import com.joetr.mira.ui.monthShortName
import com.joetr.mira.ui.sundayFirstColumn
import com.joetr.mira.ui.weekdayUpper
import com.joetr.mira.ui.motion.sharedDayCircle
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSizes
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraAccentCanvas
import com.joetr.mira.ui.theme.MiraTheme
import com.joetr.mira.ui.theme.busyThreshold
import kotlinx.coroutines.flow.collect

private const val PAGER_CENTER: Int = Int.MAX_VALUE / 2

/**
 * Keep the neighbouring months composed so their tiles resolve and load thumbnails before the
 * swipe settles. Without this the pager only composes a page once it enters the viewport, so the
 * images for the month being swiped to start loading mid-gesture and appear late.
 */
private const val PAGER_OFFSCREEN_PAGES: Int = 1

/** The desktop month rail, sitting between the app sidebar and the grid. */
private val DesktopRailWidth: Dp = 200.dp

/** Gutter between day tiles — tighter than the general day gap so squares nearly touch. */
private val CalendarTileGap: Dp = 4.dp

/** Phones have less room per tile, so the compact grid tucks the squares closer together. */
private val CompactTileGap: Dp = 3.dp

/**
 * The most row slots a Sunday-first month can need. The desktop pager always reserves this many so
 * its height and cell size never change as months with different row counts scroll past each other.
 */
private const val MaxRowsInMonth: Int = 6

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
    dayEntries: Map<LocalDateKey, List<Entry>> = emptyMap(),
    onEntryClick: ((Entry) -> Unit)? = null,
    thumbnailFor: suspend (Attachment) -> MediaSource? = { null },
) {
    MiraAccentCanvas {
        val colors = MiraTheme.colors
        val threshold = remember(activity) { busyThreshold(activity.values) }

        BoxWithConstraints(modifier.fillMaxSize().background(colors.canvas)) {
            val expanded = maxWidth >= 800.dp
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
                val containerWidth = maxWidth
                val containerHeight = maxHeight
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
                    val density = LocalDensity.current
                    val cellWidth = (containerWidth - MiraSpacing.gutter * 2) / 7
                    // Rows stay square to the column. Growing them to take leftover height only
                    // opens a wide vertical gutter between days, so the agenda gets that space
                    // instead and the grid stays tight.
                    val rowHeight = cellWidth
                    val tileGap = CompactTileGap
                    val navigatorHeight = with(density) {
                        val line = MiraTheme.type.yearNav.lineHeight.toDp()
                        line + MiraSpacing.sm +
                            (line + MiraSpacing.xs + 2.dp + MiraSpacing.xs * 2) * 2
                    }
                    val chromeHeight = with(density) {
                        MiraTheme.type.monthTitle.lineHeight.toDp() + MiraSpacing.xl +
                            MiraTheme.type.weekdayHead.lineHeight.toDp() + MiraSpacing.sm
                    }
                    val pageHeight = chromeHeight + rowHeight * visibleRows +
                        tileGap * (visibleRows - 1)
                    // Everything the tight grid does not use becomes room for the day agenda.
                    // On a short window this hits its floor and the page scrolls instead.
                    val agendaHeight = (
                        containerHeight - MiraSpacing.lg - navigatorHeight - MiraSpacing.xxxl -
                            pageHeight - MiraSpacing.md
                        ).coerceAtLeast(96.dp)

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth().height(pageHeight),
                        key = { it },
                        beyondViewportPageCount = PAGER_OFFSCREEN_PAGES,
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
                                tileGap = tileGap,
                                enableSharedTransition = page == pagerState.settledPage,
                            )
                        }
                    }

                    // The agenda always shows today, whichever month the pager is resting on.
                    Spacer(Modifier.height(MiraSpacing.md))

                    TodayAgenda(
                        today = today,
                        entries = dayEntries[today].orEmpty(),
                        onDayClick = onDayClick,
                        onEntryClick = onEntryClick,
                        onComposeClick = onComposeClick,
                        // The arrow points at the tab bar's centre +. When the floating
                        // compose button is in play instead, keep the hint arrow-free.
                        showComposeArrow = !showComposeButton,
                        modifier = Modifier.height(agendaHeight).fillMaxWidth(),
                    )
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
        val density = LocalDensity.current
        val gap = CalendarTileGap
        val headerHeight = with(density) { weekdayStyle.lineHeight.toDp() } + MiraSpacing.xl
        val cellWidth = maxWidth / 7
        // Budget every page against the tallest possible month rather than the months the pager
        // happens to be between. A neighbour-driven budget would re-measure the cells and re-centre
        // the pager each time a swipe settles, producing a visible vertical jump.
        val rowHeightFromHeight =
            (maxHeight - headerHeight - gap * (MaxRowsInMonth - 1)) / MaxRowsInMonth
        val rowHeight = minOf(rowHeightFromHeight, cellWidth).coerceAtLeast(MiraSizes.touchTarget)
        val pageHeight = headerHeight + rowHeight * MaxRowsInMonth + gap * (MaxRowsInMonth - 1)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(pageHeight),
            key = { it },
            beyondViewportPageCount = PAGER_OFFSCREEN_PAGES,
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
    val monthStyle = MiraTheme.type.yearNav.copy(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
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
                            style = monthStyle,
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

/**
 * Today's entries, sitting in the space the tight month grid leaves behind.
 *
 * Only today ever appears here; other months have no agenda. When today is empty the slot turns
 * into a small invitation, with a bobbing arrow pointing down at the compose button in the tab
 * bar. It deliberately ignores the day selection: tapping a day opens its sheet over this list,
 * so following the tap would swap the content behind a sheet the user cannot see.
 */
@Composable
private fun TodayAgenda(
    today: LocalDateKey,
    entries: List<Entry>,
    onDayClick: (LocalDateKey) -> Unit,
    onEntryClick: ((Entry) -> Unit)?,
    onComposeClick: () -> Unit,
    showComposeArrow: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append("TODAY · ")
                    append(today.weekdayUpper())
                    append(' ')
                    append(today.dayOfMonth)
                    append(' ')
                    append(monthShortName(today.monthNumber))
                },
                style = MiraTheme.type.sectionLabel,
                color = colors.accent,
                modifier = Modifier.clickable { onDayClick(today) },
            )
            Spacer(Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                Text(
                    text = "${entries.size} ${if (entries.size == 1) "entry" else "entries"}",
                    style = MiraTheme.type.meta,
                    color = colors.inkMuted,
                )
            }
        }

        Spacer(Modifier.height(MiraSpacing.sm))

        if (entries.isEmpty()) {
            AddEntryHint(
                onComposeClick = onComposeClick,
                showArrow = showComposeArrow,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MiraSpacing.md),
                contentPadding = PaddingValues(bottom = 72.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    AgendaRow(
                        entry = entry,
                        onClick = {
                            if (onEntryClick != null) onEntryClick(entry) else onDayClick(today)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The "nothing written yet" invitation. A quiet line plus a hand-drawn arrow that bobs toward the
 * compose button, so an empty day reads as an opening rather than a dead end.
 */
@Composable
private fun AddEntryHint(
    onComposeClick: () -> Unit,
    showArrow: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors
    Column(
        modifier = modifier.clickable(onClick = onComposeClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = "Nothing yet.",
            style = MiraTheme.type.entryBody,
            color = colors.inkFaint,
        )
        Spacer(Modifier.height(MiraSpacing.xs))
        Text(
            text = "A blank page for today. Tap + to start it.",
            style = MiraTheme.type.entryExcerpt,
            color = colors.inkMuted,
        )
        if (showArrow) {
            Spacer(Modifier.height(MiraSpacing.md))
            ComposeArrow()
            Spacer(Modifier.height(MiraSpacing.xs))
        }
    }
}

@Composable
private fun ComposeArrow(modifier: Modifier = Modifier) {
    val colors = MiraTheme.colors
    val bob = if (MiraTheme.motionEnabled) {
        val transition = rememberInfiniteTransition(label = "compose-hint")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "compose-hint-bob",
        )
        value
    } else {
        0f
    }

    Canvas(
        modifier = modifier
            .size(width = 52.dp, height = 46.dp)
            .graphicsLayer { translationY = bob * 6.dp.toPx() },
    ) {
        val strokeWidth = 2.2.dp.toPx()
        val w = size.width
        val h = size.height
        val tip = Offset(w * 0.5f, h * 0.82f)
        val path = Path().apply {
            moveTo(w * 0.5f, 0f)
            quadraticBezierTo(w * 0.43f, h * 0.45f, tip.x, tip.y)
        }
        drawPath(
            path = path,
            color = colors.accent,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawLine(colors.accent, tip, Offset(w * 0.28f, h * 0.6f), strokeWidth, StrokeCap.Round)
        drawLine(colors.accent, tip, Offset(w * 0.72f, h * 0.6f), strokeWidth, StrokeCap.Round)
    }
}

@Composable
private fun AgendaRow(entry: Entry, onClick: () -> Unit) {
    val colors = MiraTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiraRadii.control))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = formatTimestamp(entry.createdAt),
            style = MiraTheme.type.timestamp,
            color = colors.inkMuted,
            modifier = Modifier.width(64.dp).padding(top = 3.dp),
        )
        Spacer(Modifier.width(MiraSpacing.md))
        Text(
            text = entry.excerpt(120),
            style = MiraTheme.type.entryExcerpt,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
    tileGap: Dp = CalendarTileGap,
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
        val tileSize = (minOf(cellWidth, rowHeight) - tileGap)
            .coerceAtLeast(MiraSizes.touchTarget)

        Column(verticalArrangement = Arrangement.spacedBy(tileGap)) {
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
