package com.joetr.mira.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalInspectionMode

public val LocalSharedTransitionScope: androidx.compose.runtime.ProvidableCompositionLocal<SharedTransitionScope?> =
    compositionLocalOf { null }

public val LocalNavAnimatedVisibilityScope: androidx.compose.runtime.ProvidableCompositionLocal<AnimatedVisibilityScope?> =
    compositionLocalOf { null }

private val sharedBoundsSpec = spring<Rect>(
    dampingRatio = 0.86f,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * The calendar day circle expanding into the day screen's header.
 *
 * The circle itself is the shared bounds — it grows while the rest of the heatmap fades back, so
 * the day you tapped visibly becomes the screen you are now on.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
public fun Modifier.sharedDayCircle(date: String): Modifier {
    if (LocalInspectionMode.current || !com.joetr.mira.ui.theme.MiraTheme.motionEnabled) return this
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animatedScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@sharedDayCircle.sharedBounds(
            sharedContentState = rememberSharedContentState(key = "day_circle_$date"),
            animatedVisibilityScope = animatedScope,
            boundsTransform = { _, _ -> sharedBoundsSpec },
        )
    }
}

/** The day's title text, carried between the day screen and an entry's reading view. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
public fun Modifier.sharedDayTitle(date: String): Modifier {
    if (LocalInspectionMode.current || !com.joetr.mira.ui.theme.MiraTheme.motionEnabled) return this
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animatedScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@sharedDayTitle.sharedBounds(
            sharedContentState = rememberSharedContentState(key = "day_title_$date"),
            animatedVisibilityScope = animatedScope,
            boundsTransform = { _, _ -> sharedBoundsSpec },
        ).skipToLookaheadSize()
    }
}

/** An entry card expanding into its reading view. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
public fun Modifier.sharedEntryCard(entryId: String): Modifier {
    if (LocalInspectionMode.current || !com.joetr.mira.ui.theme.MiraTheme.motionEnabled) return this
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animatedScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@sharedEntryCard.sharedBounds(
            sharedContentState = rememberSharedContentState(key = "entry_card_$entryId"),
            animatedVisibilityScope = animatedScope,
            boundsTransform = { _, _ -> sharedBoundsSpec },
        )
    }
}
