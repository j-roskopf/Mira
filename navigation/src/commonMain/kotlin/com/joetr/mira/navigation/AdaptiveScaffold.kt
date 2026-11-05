package com.joetr.mira.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Path
import com.joetr.mira.ui.components.MiraFab
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.joetr.mira.ui.icons.MiraIcon
import com.joetr.mira.ui.icons.MiraIconPainter
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.layout.LocalBottomBarHeight
import com.joetr.mira.ui.layout.LocalWindowChromeInsets
import com.joetr.mira.ui.layout.miraSafeArea
import com.joetr.mira.ui.theme.MiraMotion
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme

@Composable
public fun AdaptiveScaffold(
    widthDp: Int,
    selected: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
    accountLabel: String? = null,
    onCompose: () -> Unit = {},
    accentCanvas: Boolean = false,
    editorSurface: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Rotation can move a phone between the bottom-bar and rail layouts. Moving the same
    // composition between them keeps the note draft, its attachments, and any open dialog alive.
    val latestContent by rememberUpdatedState(content)
    val movableContent = remember { movableContentOf { latestContent() } }
    when {
        widthDp >= 960 -> SidebarLayout(selected, onNavigate, accountLabel, accentCanvas, movableContent)
        widthDp >= 600 -> RailLayout(selected, onNavigate, accentCanvas, movableContent)
        else -> BottomBarLayout(selected, onNavigate, onCompose, accentCanvas, editorSurface, movableContent)
    }
}

@Composable
private fun SidebarLayout(
    selected: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
    accountLabel: String?,
    accentCanvas: Boolean,
    content: @Composable () -> Unit,
) {
    // Paint each column before applying insets so its colour reaches behind window chrome.
    val canvas = scaffoldCanvas(accentCanvas)
    Row(
        Modifier
            .fillMaxSize()
            .background(canvas)
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
    ) {
        Box(
            Modifier
                .width(260.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .miraSafeArea()
                .padding(28.dp),
        ) {
            Column(Modifier.fillMaxHeight()) {
                // The wordmark is chrome, not an active state — only the selected tab takes accent.
                Text(
                    "MIRA",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(MiraSpacing.xl))
                ColumnNav(selected, onNavigate)
                Spacer(Modifier.weight(1f))
                accountLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(TopLevelDestination.Settings) }
                            .padding(MiraSpacing.sm),
                    )
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .miraSafeArea().padding(28.dp),
        ) { content() }
    }
}

@Composable
private fun RailLayout(
    selected: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
    accentCanvas: Boolean,
    content: @Composable () -> Unit,
) {
    val canvas = scaffoldCanvas(accentCanvas)
    Row(
        Modifier
            .fillMaxSize()
            .background(canvas)
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
    ) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surface).miraSafeArea(),
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            destinations.forEach { dest -> RailItem(dest, selected, onNavigate) }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .miraSafeArea().padding(MiraSpacing.lg),
        ) { content() }
    }
}

/** Accent canvases cross-fade so calendar -> editor does not flash a different shell tone. */
@Composable
private fun scaffoldCanvas(accentCanvas: Boolean): Color {
    val target = if (accentCanvas) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.background
    }
    if (!MiraTheme.motionEnabled) return target
    return animateColorAsState(
        targetValue = target,
        animationSpec = tween(MiraMotion.STANDARD_MS),
        label = "scaffoldCanvas",
    ).value
}

@Composable
private fun BottomBarLayout(
    selected: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
    onCompose: () -> Unit,
    accentCanvas: Boolean,
    editorSurface: Boolean,
    content: @Composable () -> Unit,
) {
    // The editor paints its own canvas, including behind the system bars and IME, so the shell
    // keeps the same dark base instead of introducing a second tone around it.
    val canvas = scaffoldCanvas(accentCanvas)
    // Pick the bar target from the static scheme, not the animated canvas. Following `canvas`
    // makes the bar dip through the accent tone when leaving a calendar page before settling.
    val barTarget = if (accentCanvas) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.background
    }
    val bar = if (MiraTheme.motionEnabled) {
        animateColorAsState(barTarget, tween(MiraMotion.STANDARD_MS), label = "bottomBar").value
    } else {
        barTarget
    }
    val density = LocalDensity.current
    // Tall enough for the bar to sit above the system gesture area. Provided to content so each
    // route can reserve the same space without the scaffold resizing when the bar hides.
    val barHeight = with(density) {
        WindowInsets.navigationBars.getBottom(density).toDp()
    } + 108.dp
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    CompositionLocalProvider(LocalBottomBarHeight provides barHeight) {
        Box(Modifier.fillMaxSize().background(canvas)) {
            Box(Modifier.fillMaxSize()
                .windowInsetsPadding(LocalWindowChromeInsets.current)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                content()
            }
            // The bar is overlaid, not stacked. The content keeps its height (and scroll position)
            // while the bar slides away, so the calendar never re-measures mid-transition.
            AnimatedVisibility(
                visible = !editorSurface && !keyboardVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = if (MiraTheme.motionEnabled) {
                    fadeIn(tween(MiraMotion.STANDARD_MS)) +
                        slideInVertically(tween(MiraMotion.STANDARD_MS)) { it / 3 }
                } else {
                    fadeIn(tween(0))
                },
                exit = if (MiraTheme.motionEnabled) {
                    fadeOut(tween(MiraMotion.QUICK_MS)) +
                        slideOutVertically(tween(MiraMotion.QUICK_MS)) { it / 3 }
                } else {
                    fadeOut(tween(0))
                },
            ) {
                Box(Modifier.fillMaxWidth()) {
                    // A continuous raised crest cradles the circular action, like the reference.
                    Canvas(Modifier.matchParentSize()) {
                        val top = 32.dp.toPx()
                        val topCorner = 8.dp.toPx()
                        val center = size.width / 2
                        val shoulder = 54.dp.toPx()
                        val path = Path().apply {
                            moveTo(0f, top + topCorner)
                            quadraticTo(0f, top, topCorner, top)
                            lineTo(center - shoulder, top)
                            cubicTo(center - 34.dp.toPx(), top, center - 36.dp.toPx(), 0f, center, 0f)
                            cubicTo(center + 36.dp.toPx(), 0f, center + 34.dp.toPx(), top, center + shoulder, top)
                            lineTo(size.width - topCorner, top)
                            quadraticTo(size.width, top, size.width, top + topCorner)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(path, bar)
                    }
                    // Insets protect controls while the painted bar extends behind system chrome.
                    Row(Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .navigationBarsPadding()
                        .height(108.dp).padding(top = 32.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        destinations.forEachIndexed { index, dest ->
                            if (index == 2) Spacer(Modifier.width(76.dp))
                            val active = dest.topLevel == selected
                            val tint = if (active) activeNavigationTint() else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(
                                Modifier.weight(1f)
                                    .selectable(selected = active, role = Role.Tab, onClick = { onNavigate(dest.topLevel) })
                                    .semantics { contentDescription = dest.label }
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                MiraIcon(dest.icon, tint = tint, size = 24.dp)
                                Spacer(Modifier.height(4.dp))
                                // Reserve the same label space so inactive icons stay aligned.
                                Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                                    if (active) Text(dest.label, color = tint, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    MiraFab(MiraIcons.Plus, "New entry", onCompose,
                        Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun ColumnNav(
    selected: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    destinations.forEach { dest ->
        val active = dest.topLevel == selected
        val tint = if (active) {
            activeNavigationTint()
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigate(dest.topLevel) }
                .padding(vertical = MiraSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiraIcon(dest.icon, tint = tint, size = 22.dp)
            Spacer(Modifier.width(MiraSpacing.md))
            Text(
                dest.label,
                color = tint,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun RailItem(
    dest: Destination,
    selected: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    val active = dest.topLevel == selected
    val activeTint = activeNavigationTint()
    val inactiveTint = MaterialTheme.colorScheme.onSurface
    NavigationRailItem(
        selected = active,
        onClick = { onNavigate(dest.topLevel) },
        icon = {
            MiraIcon(
                dest.icon,
                tint = if (active) activeTint else inactiveTint,
                size = 22.dp,
            )
        },
        label = {
            Text(
                dest.label,
                color = if (active) activeTint else inactiveTint,
            )
        },
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = activeTint,
            selectedTextColor = activeTint,
            indicatorColor = Color.Transparent,
            unselectedIconColor = inactiveTint,
            unselectedTextColor = inactiveTint,
        ),
    )
}

/** Pale accents such as Bone still need a visible selected destination on a light bar. */
@Composable
private fun activeNavigationTint(): Color {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary.luminance()
    val surface = scheme.surface.luminance()
    val contrast = (maxOf(accent, surface) + 0.05f) / (minOf(accent, surface) + 0.05f)
    return if (contrast >= 3f) scheme.primary else scheme.onSurface
}

private data class Destination(
    val topLevel: TopLevelDestination,
    val label: String,
    val icon: MiraIconPainter,
)

private val destinations = listOf(
    Destination(TopLevelDestination.Calendar, "Calendar", MiraIcons.Calendar),
    Destination(TopLevelDestination.Timeline, "Timeline", MiraIcons.Timeline),
    Destination(TopLevelDestination.Search, "Search", MiraIcons.Search),
    Destination(TopLevelDestination.Settings, "Settings", MiraIcons.Settings),
)
