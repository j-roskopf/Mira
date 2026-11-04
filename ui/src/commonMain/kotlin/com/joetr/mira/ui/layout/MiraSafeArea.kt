package com.joetr.mira.ui.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Extra insets for desktop window chrome that Compose does not report via [WindowInsets.safeDrawing]
 * (e.g. macOS traffic lights when using a transparent title bar).
 */
public val LocalWindowChromeInsets: androidx.compose.runtime.ProvidableCompositionLocal<WindowInsets> =
    staticCompositionLocalOf { WindowInsets(0, 0, 0, 0) }

/**
 * Height reserved for the compact bottom bar, including the system gesture area it sits above.
 *
 * Routes apply this themselves so hiding the bar never changes the content's measure. Spatial
 * routes that paint edge-to-edge (the editor) opt out by not applying it.
 */
public val LocalBottomBarHeight: androidx.compose.runtime.ProvidableCompositionLocal<Dp> =
    staticCompositionLocalOf { 0.dp }

/** Keeps interactive content inside system safe areas (status bar, nav bar, cutouts, title bar). */
@Composable
public fun Modifier.miraSafeArea(): Modifier =
    this
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .windowInsetsPadding(LocalWindowChromeInsets.current)
