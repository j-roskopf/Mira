package com.joetr.mira.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.takahirom.roborazzi.captureRoboImage
import com.joetr.mira.ui.gallery.ComponentGalleryScreen
import java.io.File
import org.junit.Test

private val roboDir = File("src/desktopTest/resources/roborazzi")

class ComponentGalleryDesktopTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun componentGalleryLight() = runDesktopComposeUiTest {
        setContent {
            ComponentGalleryScreen(darkTheme = false)
        }
        onNodeWithText("Mira Components").assertExists()
        onRoot().captureRoboImage(roboDir.resolve("ComponentGallery_light.png").absolutePath)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun componentGalleryDark() = runDesktopComposeUiTest {
        setContent {
            ComponentGalleryScreen(darkTheme = true)
        }
        onRoot().captureRoboImage(roboDir.resolve("ComponentGallery_dark.png").absolutePath)
    }
}
