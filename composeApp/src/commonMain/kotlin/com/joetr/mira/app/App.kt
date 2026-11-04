package com.joetr.mira.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import com.joetr.mira.feature.day.DayEntriesSheet
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.joetr.mira.ui.components.MiraConfirmDialog
import com.joetr.mira.ui.layout.MiraContentWidth
import com.joetr.mira.ui.motion.LocalNavAnimatedVisibilityScope
import com.joetr.mira.ui.motion.LocalSharedTransitionScope
import com.joetr.mira.ui.motion.navSharedElementTransitionSpec
import com.joetr.mira.ui.motion.miraDateTransition
import com.joetr.mira.ui.motion.miraHierarchyTransition
import com.joetr.mira.ui.theme.MiraAccent
import com.joetr.mira.ui.theme.MiraTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
public fun App(graph: AppGraph, initialWebPath: String? = null, motionEnabled: Boolean = true) {
    val clock = remember { SystemClockZone() }
    val scope = rememberCoroutineScope()

    val themeMode by graph.settings.observeThemeMode().collectAsState(ThemeMode.SYSTEM)
    val accentName by graph.settings.observeAccent().collectAsState("EMBER")
    val session by graph.session.observeSession()
        .collectAsState(SessionState.LocalPending(""))
    val pendingUpdateInstallConfirmation by graph.updates.pendingInstallConfirmation.collectAsState()
    val syncRevision by graph.sync.revision.collectAsState()

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
    val screenKey = if (current is DayKey) {
        backStack.dropLast(1).lastOrNull() ?: CalendarKey(current.date.take(7))
    } else current
    var refreshVersion by remember { mutableStateOf(0) }

    var selectedDate by remember { mutableStateOf<LocalDateKey?>(null) }
    var activity by remember { mutableStateOf<Map<LocalDateKey, Int>>(emptyMap()) }
    var timeline by remember { mutableStateOf<List<Entry>>(emptyList()) }

    val ownerId = session.ownerId
    val month = remember(screenKey) {
        (screenKey as? CalendarKey)?.month?.let { MonthKey.parseOrNull(it) }
            ?: MonthKey.of(clock.today().toLocalDate())
    }

    fun refresh() {
        refreshVersion++
    }

    // A sync can pull entries after the screen has already loaded (startup, sign-in, manual
    // "Sync now"). Reload whenever one completes so the calendar is not left showing stale data.
    var seenSyncRevision by remember { mutableStateOf(syncRevision) }
    LaunchedEffect(syncRevision) {
        if (syncRevision != seenSyncRevision) {
            seenSyncRevision = syncRevision
            refresh()
        }
    }

    // Cancel obsolete loads when month navigation interrupts an in-flight request.
    LaunchedEffect(ownerId, month, refreshVersion) {
        activity = graph.entries.activityForMonth(ownerId, month)
        timeline = graph.entries.timeline(ownerId, limit = 200, offset = 0)
    }

    val navigationState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(state = navigationState, isBackEnabled = backStack.size > 1) {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    MiraTheme(darkTheme = dark, accent = MiraAccent.fromName(accentName), motionEnabled = motionEnabled) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
            val maxWidth = this.maxWidth
            SharedTransitionLayout(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                AdaptiveScaffold(
                    widthDp = maxWidth.value.toInt(),
                    selected = screenKey.topLevel(),
                    accentCanvas = screenKey is CalendarKey || screenKey is YearKey,
                    editorSurface = screenKey is ComposeKey,
                    onCompose = { backStack.add(ComposeKey(date = clock.today().value)) },
                    onNavigate = { destination ->
                        // Returning to the calendar always lands on today, even from another month.
                        if (destination == TopLevelDestination.Calendar) {
                            selectedDate = clock.today()
                        }
                        backStack.clear()
                        backStack.add(destination.rootKey())
                    },
                ) {
                    val dateTransition = miraDateTransition()
                    val hierarchyTransition = miraHierarchyTransition()
                    val defaultTransition = navSharedElementTransitionSpec()
                    AnimatedContent(
                        targetState = screenKey,
                        contentKey = { if (it is CalendarKey) CalendarKey::class else it },
                        transitionSpec = {
                            val from = initialState
                            val to = targetState
                            when {
                                from is YearKey && to is YearKey -> dateTransition(to.year.compareTo(from.year))
                                from is CalendarKey && to is YearKey -> hierarchyTransition(false)
                                from is YearKey && to is CalendarKey -> hierarchyTransition(true)
                                else -> defaultTransition()
                            }
                        },
                        label = "screen",
                    ) { key ->
                        CompositionLocalProvider(
                            LocalNavAnimatedVisibilityScope provides this@AnimatedContent,
                        ) {
                            MiraContentWidth(
                                Modifier.fillMaxSize(),
                                maxWidth = when (key) {
                                    is CalendarKey -> 1200.dp
                                    // Spatial screens and the editor fill the window rather than
                                    // being squeezed into the reading measure.
                                    is YearKey -> Dp.Infinity
                                    is AuthKey -> Dp.Infinity
                                    is ComposeKey -> Dp.Infinity
                                    else -> 720.dp
                                },
                            ) {
                            Screen(
                                key = key,
                                graph = graph,
                                ownerId = ownerId,
                                today = clock.today(),
                                selectedDate = selectedDate,
                                showComposeButton = maxWidth >= 600.dp,
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
                                refreshVersion = refreshVersion,
                            )
                            }
                        }
                    }
                }
            }
            }
            if (current is DayKey) {
                val date = LocalDateKey(current.date)
                var entries by remember(date, ownerId) { mutableStateOf<List<Entry>>(emptyList()) }
                var loading by remember(date, ownerId) { mutableStateOf(true) }
                var error by remember(date, ownerId) { mutableStateOf<String?>(null) }
                var retry by remember(date, ownerId) { mutableStateOf(0) }
                LaunchedEffect(date, ownerId, refreshVersion, retry) {
                    loading = true
                    error = null
                    try {
                        entries = graph.entries.entriesForDate(ownerId, date)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "Could not load this day's entries. Please try again."
                    } finally {
                        loading = false
                    }
                }
                DayEntriesSheet(
                    date = date,
                    entries = entries,
                    loading = loading,
                    error = error,
                    onRetry = { retry++ },
                    onDismiss = {
                        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                        else backStack[0] = CalendarKey(date.value.take(7))
                    },
                    onEntryClick = { backStack.add(ComposeKey(entryId = it.id, date = date.value)) },
                    onCompose = { backStack.add(ComposeKey(date = date.value)) },
                )
            }
            pendingUpdateInstallConfirmation?.let { update ->
                MiraConfirmDialog(
                    onDismissRequest = { graph.updates.respondToInstallConfirmation(false) },
                    title = "Install Mira ${update.versionName}?",
                    message = "The update is downloaded and verified. Mira will close and open " +
                        "the installer; it relaunches when the platform allows it.",
                    confirmText = "Close and install",
                    dismissText = "Not now",
                    onConfirm = { graph.updates.respondToInstallConfirmation(true) },
                )
            }
        }
    }
}

@Composable
private fun Screen(
    key: Any,
    graph: AppGraph,
    ownerId: String,
    today: LocalDateKey,
    selectedDate: LocalDateKey?,
    showComposeButton: Boolean,
    activity: Map<LocalDateKey, Int>,
    timeline: List<Entry>,
    onNavigate: (Any) -> Unit,
    onBack: () -> Unit,
    onReplaceTop: (Any) -> Unit,
    onSelectDate: (LocalDateKey) -> Unit,
    onRefresh: () -> Unit,
    refreshVersion: Int,
) {
    val scope = rememberCoroutineScope()

    when (key) {
        is CalendarKey -> CalendarScreen(
            month = key.month?.let { MonthKey.parseOrNull(it) } ?: MonthKey.of(today.toLocalDate()),
            today = today,
            activity = activity,
            selected = selectedDate,
            showComposeButton = showComposeButton,
            onMonthChange = { onReplaceTop(CalendarKey(it.value)) },
            onDayClick = { date ->
                onSelectDate(date)
                if ((activity[date] ?: 0) == 0) {
                    onNavigate(ComposeKey(date = date.value))
                } else {
                    onNavigate(DayKey(date.value))
                }
            },
            onYearClick = { onNavigate(YearKey(it)) },
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

        is ComposeKey -> {
            val recorder = remember { createMediaRecorderController() }
            androidx.compose.runtime.DisposableEffect(recorder) {
                onDispose { recorder.release() }
            }
            val date = key.date?.let { LocalDateKey(it) } ?: today
            var body by remember(key) { mutableStateOf("") }
            var attachments by remember(key) {
                mutableStateOf<List<com.joetr.mira.domain.Attachment>>(emptyList())
            }
            var tags by remember(key) { mutableStateOf<List<String>>(emptyList()) }
            var recentTags by remember { mutableStateOf<List<String>>(emptyList()) }
            var confirmDelete by remember(key) { mutableStateOf(false) }
            var entryError by remember(key) { mutableStateOf<String?>(null) }
            var loaded by remember(key) { mutableStateOf(key.entryId == null) }
            var saving by remember(key) { mutableStateOf(false) }
            var deleting by remember(key) { mutableStateOf(false) }
            var stagingRecording by remember(key) { mutableStateOf(false) }

            val deletableId = key.entryId
            if (confirmDelete && deletableId != null) {
                MiraConfirmDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = "Delete this entry?",
                    message = "It will be removed from every device, and any audio or video with " +
                        "it is deleted from storage. The GitHub archive keeps earlier versions.",
                    confirmText = "Delete",
                    destructive = true,
                    onConfirm = {
                        confirmDelete = false
                        if (!deleting) {
                            deleting = true
                            scope.launch {
                                try {
                                    val existing = graph.entries.entry(deletableId)
                                    graph.entries.delete(deletableId)
                                    existing?.let { graph.media.deleteMediaFor(it.id, it.attachments) }
                                    graph.onLocalWrite()
                                    onRefresh()
                                    onBack()
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    entryError = "Could not delete this entry. Please try again."
                                    deleting = false
                                }
                            }
                        }
                    },
                )
            }

            LaunchedEffect(key.entryId, ownerId) {
                recentTags = runCatching { graph.entries.recentTags(ownerId) }
                    .getOrDefault(emptyList())
                key.entryId?.let { id ->
                    try {
                        val existing = graph.entries.entry(id)
                        if (existing == null) {
                            entryError = "This entry is no longer available."
                        } else {
                            body = existing.body
                            attachments = existing.attachments
                            tags = existing.tags
                            loaded = true
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        entryError = "Could not load this entry. Go back and try again."
                    }
                }
            }

            if (!loaded) {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(entryError ?: "Loading entry…")
                    androidx.compose.material3.TextButton(onClick = onBack) {
                        androidx.compose.material3.Text("Back")
                    }
                }
                return
            }

            ComposeEntryScreen(
                date = date,
                saving = saving || stagingRecording,
                deleting = deleting,
                errorMessage = entryError,
                initialBody = body,
                recorder = recorder,
                attachments = attachments,
                tags = tags,
                recentTags = recentTags,
                onAddTag = { tag ->
                    if (tags.none { it.equals(tag, ignoreCase = true) }) tags = tags + tag
                },
                onRemoveTag = { tag ->
                    tags = tags.filterNot { it.equals(tag, ignoreCase = true) }
                },
                sourceFor = { graph.media.sourceFor(it) },
                onRemoveAttachment = { attachment ->
                    attachments = attachments.filterNot { it.id == attachment.id }
                },
                initialCapture = when (key.initialCapture) {
                    "audio" -> CaptureKind.AUDIO
                    "video" -> CaptureKind.VIDEO
                    else -> null
                },
                onBodyChange = { body = it },
                onDateClick = {},
                onCaptureFinished = { result ->
                    stagingRecording = true
                    scope.launch {
                        try {
                            val attachment = graph.attachFrom(result, key.entryId)
                            attachments = attachments + attachment
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            entryError = "Could not save the recording. Please try recording again."
                        } finally {
                            stagingRecording = false
                        }
                    }
                },
                onMediaPicked = { results ->
                    if (results.isNotEmpty()) {
                        stagingRecording = true
                        scope.launch {
                            try {
                                results.forEach { result ->
                                    attachments = attachments + graph.attachFrom(result, key.entryId)
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                entryError = "Could not attach that media. Please try again."
                            } finally {
                                stagingRecording = false
                            }
                        }
                    }
                },
                onSave = {
                    if (!saving && !stagingRecording) scope.launch {
                        saving = true
                        try {
                            val existing = key.entryId?.let { graph.entries.entry(it) }
                            if (key.entryId == null) {
                                graph.entries.create(
                                    ownerId = ownerId,
                                    body = body,
                                    date = date,
                                    attachments = attachments,
                                    tags = tags,
                                )
                            } else {
                                graph.entries.save(
                                    requireNotNull(existing).copy(
                                        body = body,
                                        attachments = attachments,
                                        tags = tags,
                                    ),
                                )
                            }
                            graph.onEntrySaved(ownerId)
                            onRefresh()
                            onBack()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            entryError = "Could not save your entry. Please try again."
                        } finally {
                            saving = false
                        }
                    }
                },
                onCancel = {
                    recorder.cancel()
                    onBack()
                },
                onDelete = key.entryId?.let { { confirmDelete = true } },
            )
        }

        is TimelineKey -> TimelineScreen(
            entries = timeline,
            onEntryClick = { onNavigate(ComposeKey(entryId = it.id, date = it.localDate.value)) },
            today = today,
        )

        is SearchKey -> {
            var query by remember { mutableStateOf(key.query.orEmpty()) }
            var filters by remember {
                mutableStateOf(com.joetr.mira.data.entries.SearchFilters())
            }
            var results by remember { mutableStateOf<List<Entry>>(emptyList()) }
            var availableTags by remember { mutableStateOf<List<String>>(emptyList()) }

            LaunchedEffect(query, filters, ownerId) {
                results = graph.entries.search(ownerId, query, filters)
            }

            LaunchedEffect(ownerId, refreshVersion) {
                availableTags = runCatching { graph.entries.recentTags(ownerId) }
                    .getOrDefault(emptyList())
            }

            SearchScreen(
                query = query,
                filters = filters,
                results = results,
                availableTags = availableTags,
                onQueryChange = { query = it },
                onFiltersChange = { filters = it },
                onEntryClick = {
                    onNavigate(ComposeKey(entryId = it.id, date = it.localDate.value))
                },
            )
        }

        is SettingsKey -> SettingsRoute(
            graph = graph,
            ownerId = ownerId,
            onBack = onBack,
            onSignIn = { onNavigate(AuthKey) },
            onDataDeleted = onRefresh,
        )

        is AuthKey -> AuthScreen(
            viewModel = graph.authViewModel,
            onDone = onBack,
            onBack = onBack,
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
