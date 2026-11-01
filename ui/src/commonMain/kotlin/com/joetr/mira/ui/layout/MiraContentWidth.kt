package com.joetr.mira.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Entry text is meant to be read, and Compose will happily run a line of it across a 2560px
 * monitor. 720dp keeps the measure near the 68-character cap in DESIGN.md.
 * Spatial screens such as the calendar can opt into a wider canvas.
 */
private val MaxContentWidth = 720.dp

@Composable
public fun MiraContentWidth(
    modifier: Modifier = Modifier,
    maxWidth: Dp = MaxContentWidth,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        Box(
            // widthIn must come first: fillMaxWidth pins minWidth to the parent's max, and a
            // later widthIn cannot shrink below that min, so the cap would be ignored.
            Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .align(Alignment.TopCenter),
        ) {
            content()
        }
    }
}
