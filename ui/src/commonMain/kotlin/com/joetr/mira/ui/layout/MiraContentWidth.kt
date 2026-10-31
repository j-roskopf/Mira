package com.joetr.mira.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val MaxContentWidth = 1280.dp

@Composable
public fun MiraContentWidth(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .widthIn(max = MaxContentWidth)
                .align(Alignment.TopCenter),
        ) {
            content()
        }
    }
}
