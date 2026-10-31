package com.joetr.mira.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.joetr.mira.di.createMiraAppGraph
import com.joetr.mira.platform.configureMacOsDesktopAppearance
import com.joetr.mira.platform.desktopFileDialogOwner
import com.joetr.mira.ui.layout.LocalWindowChromeInsets
import androidx.compose.ui.graphics.Color as ComposeColor
import java.awt.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private val isMac: Boolean =
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

/** Fallback when AWT has not yet reported title-bar insets. */
private val MacTitleBarFallback = 52.dp

public fun main() {
    configureMacOsDesktopAppearance()
    val graph = runBlocking { createMiraAppGraph() }
    application {
        val windowState = rememberWindowState(width = 1920.dp, height = 1080.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = "Mira",
            state = windowState,
        ) {
            val density = LocalDensity.current
            var chromeInsets by remember {
                mutableStateOf(
                    if (isMac) WindowInsets(top = MacTitleBarFallback) else WindowInsets(0, 0, 0, 0),
                )
            }

            // Apply during composition so the transparent title bar sticks on macOS.
            SideEffect {
                desktopFileDialogOwner = window as? java.awt.Frame
                if (isMac) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
                // Matches the dark canvas token so the window never flashes white on open.
                val bg = ComposeColor(0xFF0A0A0B)
                window.background = Color(bg.toArgb(), true)
            }

            LaunchedEffect(window) {
                if (!isMac) return@LaunchedEffect
                repeat(5) {
                    val topPx = window.insets.top
                    if (topPx > 0) {
                        chromeInsets = WindowInsets(top = with(density) { topPx.toDp() })
                        return@LaunchedEffect
                    }
                    delay(50)
                }
            }

            CompositionLocalProvider(LocalWindowChromeInsets provides chromeInsets) {
                App(graph)
            }
        }
    }
}
