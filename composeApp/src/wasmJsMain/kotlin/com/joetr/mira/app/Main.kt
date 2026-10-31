package com.joetr.mira.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.joetr.mira.di.createMiraAppGraph
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.configureWebResources

@OptIn(ExperimentalComposeUiApi::class)
public fun main() {
    // Compose resources default to "./$path" resolved against window.location.pathname.
    // On deep SPA routes (e.g. /recipe/...), that 404s and painterResource stays blank.
    configureWebResources {
        resourcePathMapping { path -> "/$path" }
    }
    MainScope().launch {
        try {
            val graph = createMiraAppGraph()
            ComposeViewport(viewportContainerId = "composeApp") {
                App(graph, initialWebPath = readWebPath())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (window.location.pathname.contains("auth-callback")) {
                window.history.replaceState(null, "", "/")
            }
            window.document.body?.innerHTML =
                "<pre style='padding:1rem;font-family:system-ui'>Failed to start Mira: ${e.message ?: e}</pre>"
        }
    }
}

private fun readWebPath(): String? = window.location.pathname.takeIf { it != "/" }
