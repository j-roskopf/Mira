package com.joetr.mira.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.joetr.mira.ui.theme.MiraTheme

/** Short, interruptible travel preserves chronology without moving a whole screen width. */
@Composable
public fun miraDateTransition(): (Int) -> ContentTransform {
    val enabled = MiraTheme.motionEnabled
    val scheme = MaterialTheme.motionScheme
    val layoutSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    return { direction ->
        if (!enabled) {
            EnterTransition.None togetherWith ExitTransition.None
        } else {
            val sign = direction.coerceIn(-1, 1) * layoutSign
            ((slideInHorizontally(scheme.defaultSpatialSpec()) { sign * it / 12 } +
                fadeIn(scheme.defaultEffectsSpec())) togetherWith
                (slideOutHorizontally(scheme.defaultSpatialSpec()) { -sign * it / 12 } +
                    fadeOut(scheme.fastEffectsSpec()))).withoutSizeAnimation()
        }
    }
}

/** Zoom out to the year overview; move back in when choosing a month. */
@Composable
public fun miraHierarchyTransition(): (Boolean) -> ContentTransform {
    val enabled = MiraTheme.motionEnabled
    val scheme = MaterialTheme.motionScheme
    return { enteringMonth ->
        if (!enabled) {
            EnterTransition.None togetherWith ExitTransition.None
        } else {
            ((scaleIn(scheme.defaultSpatialSpec(), initialScale = if (enteringMonth) .96f else 1.04f) +
                fadeIn(scheme.defaultEffectsSpec())) togetherWith
                (scaleOut(scheme.defaultSpatialSpec(), targetScale = if (enteringMonth) 1.04f else .96f) +
                    fadeOut(scheme.fastEffectsSpec()))).withoutSizeAnimation()
        }
    }
}

internal fun ContentTransform.withoutSizeAnimation(): ContentTransform = ContentTransform(
    targetContentEnter = targetContentEnter,
    initialContentExit = initialContentExit,
    sizeTransform = null,
)
