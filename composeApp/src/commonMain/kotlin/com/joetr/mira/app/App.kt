package com.joetr.mira.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.joetr.mira.di.AppGraph
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.domain.SessionState
import com.joetr.mira.domain.SystemClockZone
import com.joetr.mira.domain.ThemeMode
import com.joetr.mira.domain.ownerId
import com.joetr.mira.feature.auth.AuthScreen
import com.joetr.mira.feature.calendar.CalendarScreen
import com.joetr.mira.feature.calendar.YearScreen
import com.joetr.mira.feature.compose.ComposeEntryScreen
import com.joetr.mira.feature.day.DayScreen
import com.joetr.mira.feature.search.SearchScreen
import com.joetr.mira.feature.timeline.TimelineScreen
import com.joetr.mira.media.CaptureKind
import com.joetr.mira.media.createMediaRecorderController
import com.joetr.mira.navigation.AdaptiveScaffold
import com.joetr.mira.navigation.AuthKey
import com.joetr.mira.navigation.CalendarKey
import com.joetr.mira.navigation.ComposeKey
import com.joetr.mira.navigation.DayKey
import com.joetr.mira.navigation.SearchKey
import com.joetr.mira.navigation.SettingsKey
import com.joetr.mira.navigation.TimelineKey
import com.joetr.mira.navigation.TopLevelDestination
import com.joetr.mira.navigation.YearKey
import com.joetr.mira.navigation.parseWebRoute
import com.joetr.mira.ui.motion.LocalNavAnimatedVisibilityScope
import com.joetr.mira.ui.motion.LocalSharedTransitionScope
import com.joetr.mira.ui.motion.navSharedElementTransitionSpec
import com.joetr.mira.ui.theme.MiraAccent
import com.joetr.mira.ui.theme.MiraTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
public fun App(graph: AppGraph, initialWebPath: String? = null) {
    val clock = remember { SystemClockZone() }
    val scope = rememberCoroutineScope()

    val themeMode by graph.settings.observeThemeMode().collectAsState(ThemeMode.SYSTEM)
    val accentName by graph.settings.observeAccent().collectAsState("EMBER")
    val session by graph.session.observeSession()
        .collectAsState(SessionState.LocalPending(""))

    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    // A deep link decides the first screen; everything else starts on the calendar.
    val backStack = remember {
        mutableStateListOf<Any>(
            initialWebPath?.let { parseWebRoute(it) } ?: CalendarKey(),
        )
    }
    val current = backStack.last()

    var selectedDate by remember { mutableStateOf<LocalDateKey?>(null) }
    var entriesForDay by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var activity by remember { mutableStateOf<Map<LocalDateKey, Int>>(emptyMap()) }
    var timeline by remember { mutableStateOf<List<Entry>>(emptyList()) }

    val ownerId = session.ownerId
    val month = remember(current) {
        (current as? CalendarKey)?.month?.let { MonthKey.parseOrNull(it) }
            ?: MonthKey.of(clock.today().toLocalDate())
    }

    fun refresh() {
        scope.launch {
            activity = graph.entries.activityForMonth(ownerId, month)
            selectedDate?.let { entriesForDay = graph.entries.entriesForDate(ownerId, it) }
            timeline = graph.entries.timeline(ownerId, limit = 200, offset = 0)
        }
    }

    LaunchedEffect(ownerId, month) { refresh() }

    val navigationState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(state = navigationState, isBackEnabled = backStack.size > 1) {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    MiraTheme(darkTheme = dark, accent = MiraAccent.fromName(accentName)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
            val maxWidth = this.maxWidth
            SharedTransitionLayout(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                AdaptiveScaffold(
                    widthDp = maxWidth.value.toInt(),
                    selected = current.topLevel(),
                    onNavigate = { destination ->
                        backStack.clear()
                        backStack.add(destination.rootKey())
                    },
                ) {
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = navSharedElementTransitionSpec(),
                        label = "screen",
                    ) { key ->
                        CompositionLocalProvider(
                            LocalNavAnimatedVisibilityScope provides this@AnimatedContent,
                        ) {
                            Screen(
                                key = key,
                                graph = graph,
                                ownerId = ownerId,
                                month = month,
                                today = clock.today(),
                                selectedDate = selectedDate,
                                entriesForDay = entriesForDay,
                                activity = activity,
                                timeline = timeline,
                                onNavigate = { backStack.add(it) },
                                onBack = {
                                    if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                },
                                onReplaceTop = { replacement ->
                                    backStack[backStack.lastIndex] = replacement
                                },
                                onSelectDate = { selectedDate = it },
                                onRefresh = ::refresh,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun Screen(
    key: Any,
    graph: AppGraph,
    ownerId: String,
    month: MonthKey,
    today: LocalDateKey,
    selectedDate: LocalDateKey?,
    entriesForDay: List<Entry>,
    activity: Map<LocalDateKey, Int>,
    timeline: List<Entry>,
    onNavigate: (Any) -> Unit,
    onBack: () -> Unit,
    onReplaceTop: (Any) -> Unit,
    onSelectDate: (LocalDateKey) -> Unit,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    when (key) {
        is CalendarKey -> CalendarScreen(
            month = month,
            today = today,
            activity = activity,
            selected = selectedDate,
            onMonthChange = { onReplaceTop(CalendarKey(it.value)) },
            onDayClick = { date ->
                onSelectDate(date)
                onNavigate(DayKey(date.value))
            },
            onYearClick = { onNavigate(YearKey(it)) },
            onSettingsClick = { onNavigate(SettingsKey) },
            onComposeClick = { onNavigate(ComposeKey(date = today.value)) },
        )

        is YearKey -> {
            var yearActivity by remember(key.year) {
                mutableStateOf<List<com.joetr.mira.domain.DayActivity>>(emptyList())
            }
            LaunchedEffect(key.year, ownerId) {
                yearActivity = graph.entries.activityForYear(ownerId, key.year)
            }
            YearScreen(
                year = key.year,
                activity = yearActivity,
                onMonthClick = { onReplaceTop(CalendarKey(it.value)) },
                onBack = onBack,
                onYearChange = { onReplaceTop(YearKey(it)) },
            )
        }

        is DayKey -> {
            val date = LocalDateKey(key.date)
            DayScreen(
                date = date,
                entries = entriesForDay,
                onBack = onBack,
                onEntryClick = { onNavigate(ComposeKey(entryId = it.id, date = key.date)) },
                onCompose = { onNavigate(ComposeKey(date = key.date)) },
                onPreviousDay = {},
                onNextDay = {},
            )
        }

        is ComposeKey -> {
            val recorder = remember { createMediaRecorderController() }
            val date = key.date?.let { LocalDateKey(it) } ?: today
            var body by remember(key) { mutableStateOf("") }
            var attachments by remember(key) {
                mutableStateOf<List<com.joetr.mira.domain.Attachment>>(emptyList())
            }

            LaunchedEffect(key.entryId) {
                key.entryId?.let { id ->
                    graph.entries.entry(id)?.let { existing ->
                        body = existing.body
                        attachments = existing.attachments
                    }
                }
            }

            ComposeEntryScreen(
                date = date,
                initialBody = body,
                recorder = recorder,
                attachmentCount = attachments.size,
                initialCapture = when (key.initialCapture) {
                    "audio" -> CaptureKind.AUDIO
                    "video" -> CaptureKind.VIDEO
                    else -> null
                },
                onBodyChange = { body = it },
                onDateClick = {},
                onCaptureFinished = { result ->
                    scope.launch {
                        val attachment = graph.attachFrom(result, key.entryId)
                        attachments = attachments + attachment
                    }
                },
                onSave = {
                    scope.launch {
                        val existing = key.entryId?.let { graph.entries.entry(it) }
                        if (existing == null) {
                            graph.entries.create(
                                ownerId = ownerId,
                                body = body,
                                date = date,
                                attachments = attachments,
                            )
                        } else {
                            graph.entries.save(
                                existing.copy(body = body, attachments = attachments),
                            )
                        }
                        graph.onLocalWrite()
                        onRefresh()
                        onBack()
                    }
                },
                onCancel = {
                    recorder.cancel()
                    onBack()
                },
            )
        }

        is TimelineKey -> TimelineScreen(
            entries = timeline,
            onEntryClick = { onNavigate(ComposeKey(entryId = it.id, date = it.localDate.value)) },
        )

        is SearchKey -> {
            var query by remember { mutableStateOf(key.query.orEmpty()) }
            var filters by remember {
                mutableStateOf(com.joetr.mira.data.entries.SearchFilters())
            }
            var results by remember { mutableStateOf<List<Entry>>(emptyList()) }

            LaunchedEffect(query, filters, ownerId) {
                results = graph.entries.search(ownerId, query, filters)
            }

            SearchScreen(
                query = query,
                filters = filters,
                results = results,
                onQueryChange = { query = it },
                onFiltersChange = { filters = it },
                onEntryClick = {
                    onNavigate(ComposeKey(entryId = it.id, date = it.localDate.value))
                },
            )
        }

        is SettingsKey -> SettingsRoute(graph = graph, ownerId = ownerId, onBack = onBack)

        is AuthKey -> AuthScreen(
            viewModel = graph.authViewModel,
            onDone = onBack,
        )

        else -> Unit
    }
}

private fun Any.topLevel(): TopLevelDestination = when (this) {
    is TimelineKey -> TopLevelDestination.Timeline
    is SearchKey -> TopLevelDestination.Search
    is SettingsKey -> TopLevelDestination.Settings
    else -> TopLevelDestination.Calendar
}

private fun TopLevelDestination.rootKey(): Any = when (this) {
    TopLevelDestination.Calendar -> CalendarKey()
    TopLevelDestination.Timeline -> TimelineKey
    TopLevelDestination.Search -> SearchKey()
    TopLevelDestination.Settings -> SettingsKey
}
