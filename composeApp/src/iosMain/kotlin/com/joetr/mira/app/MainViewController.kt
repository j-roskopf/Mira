package com.joetr.mira.app

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification
import com.joetr.mira.di.createMiraAppGraph
import kotlinx.coroutines.runBlocking

@Suppress("unused")
public fun MainViewController() = ComposeUIViewController {
    val graph = runBlocking { createMiraAppGraph() }
    var reduceMotion by remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            UIAccessibilityReduceMotionStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { reduceMotion = UIAccessibilityIsReduceMotionEnabled() }
        onDispose { center.removeObserver(observer) }
    }
    App(graph, motionEnabled = !reduceMotion)
}
