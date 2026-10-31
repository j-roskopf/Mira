package com.joetr.mira.ui.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Extra insets for desktop window chrome that Compose does not report via [WindowInsets.safeDrawing]
 * (e.g. macOS traffic lights when using a transparent title bar).
 */
public val LocalWindowChromeInsets: androidx.compose.runtime.ProvidableCompositionLocal<WindowInsets> =
    staticCompositionLocalOf { WindowInsets(0, 0, 0, 0) }

/** Keeps interactive content inside system safe areas (status bar, nav bar, cutouts, title bar). */
@Composable
public fun Modifier.miraSafeArea(): Modifier =
    this
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .windowInsetsPadding(LocalWindowChromeInsets.current)
