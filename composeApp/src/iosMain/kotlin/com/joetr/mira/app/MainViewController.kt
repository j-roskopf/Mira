package com.joetr.mira.app

import androidx.compose.ui.window.ComposeUIViewController
import com.joetr.mira.di.createMiraAppGraph
import kotlinx.coroutines.runBlocking

@Suppress("unused")
public fun MainViewController() = ComposeUIViewController {
    val graph = runBlocking { createMiraAppGraph() }
    App(graph)
}
