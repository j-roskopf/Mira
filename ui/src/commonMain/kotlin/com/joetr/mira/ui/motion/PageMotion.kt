package com.joetr.mira.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.joetr.mira.ui.theme.MiraTheme

/** How the next screen relates to the one on screen, so motion can match the gesture. */
public enum class NavDirection {
    /** Pushing deeper into the stack (list -> detail). */
    Forward,

    /** Popping back towards the root. */
    Backward,

    /** Switching between roots, or replacing a screen in place. */
    Crossfade,
}

/**
 * One page transition for every route, on every platform.
 *
 * Forward and backward mirror each other and respect layout direction; tab switches and in-place
 * replacements stay a plain crossfade. Travel is deliberately short — see the date paging and
 * hierarchy transitions, which use the same calm distance.
 */
@Composable
public fun miraPageTransition(direction: NavDirection): ContentTransform {
    if (!MiraTheme.motionEnabled) {
        return EnterTransition.None togetherWith ExitTransition.None
    }

    val scheme = MaterialTheme.motionScheme
    if (direction == NavDirection.Crossfade) {
        return (fadeIn(scheme.defaultEffectsSpec()) togetherWith
            fadeOut(scheme.fastEffectsSpec())).withoutSizeAnimation()
    }

    val layoutSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val sign = (if (direction == NavDirection.Forward) 1 else -1) * layoutSign
    return ((slideInHorizontally(scheme.defaultSpatialSpec()) { sign * it / 8 } +
        fadeIn(scheme.defaultEffectsSpec())) togetherWith
        (slideOutHorizontally(scheme.defaultSpatialSpec()) { -sign * it / 8 } +
            fadeOut(scheme.fastEffectsSpec()))).withoutSizeAnimation()
}

/**
 * The editor is a sheet, not a page: it rises over the screen it came from and settles back down
 * on the way out. Vertical travel reads as a modal and avoids sliding a full-height text surface
 * horizontally while the tab bar and IME are also changing the available height.
 */
@Composable
public fun miraEditorTransition(forward: Boolean): ContentTransform {
    if (!MiraTheme.motionEnabled) {
        return EnterTransition.None togetherWith ExitTransition.None
    }

    val scheme = MaterialTheme.motionScheme
    val sign = if (forward) 1 else -1
    return ((slideInVertically(scheme.defaultSpatialSpec()) { sign * it / 16 } +
        fadeIn(scheme.defaultEffectsSpec())) togetherWith
        (slideOutVertically(scheme.defaultSpatialSpec()) { -sign * it / 16 } +
            fadeOut(scheme.fastEffectsSpec()))).withoutSizeAnimation()
}
