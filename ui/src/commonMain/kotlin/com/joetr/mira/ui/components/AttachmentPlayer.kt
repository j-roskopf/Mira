package com.joetr.mira.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.ui.formatDuration
import com.joetr.mira.ui.icons.MiraIcon
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme

/**
 * What the player UI needs to know, without the UI module depending on `core:media`.
 */
public data class AttachmentPlaybackState(
    val playing: Boolean = false,
    val preparing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val errorMessage: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
) {
    public val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * An audio attachment: play/pause, a scrubber, and the time.
 *
 * No waveform. Mira does not analyse audio, and a fake waveform would be decoration pretending to
 * be data — the progress bar says the only thing that is actually true.
 */
@Composable
public fun AudioAttachmentRow(
    attachment: Attachment,
    state: AttachmentPlaybackState,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors
    val duration = state.durationMs.takeIf { it > 0 } ?: attachment.durationMs

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiraRadii.card))
            .background(colors.surfaceRaised)
            .padding(MiraSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiraSpacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(colors.accent, CircleShape)
                .clickable(enabled = !state.preparing, onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            MiraIcon(
                painter = if (state.playing) MiraIcons.Pause else MiraIcons.Play,
                tint = colors.onAccent,
                size = 16.dp,
            )
        }

        Column(Modifier.weight(1f)) {
            Scrubber(progress = state.progress, onSeek = onSeek)
            Spacer(Modifier.height(MiraSpacing.xs))
            Row {
                Text(
                    text = formatDuration(state.positionMs),
                    style = MiraTheme.type.meta,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatDuration(duration),
                    style = MiraTheme.type.meta,
                    color = colors.inkMuted,
                )
            }
        }

        if (!attachment.isUploaded) {
            MiraIcon(MiraIcons.CloudPending, tint = colors.accent, size = 16.dp)
        }
    }

    state.errorMessage?.let { message ->
        Spacer(Modifier.height(MiraSpacing.xs))
        Text(message, style = MiraTheme.type.meta, color = colors.error)
    }
}

/**
 * A video attachment.
 *
 * [surface] is the platform's video output, placed only once playback has started — before that
 * the tile shows a play affordance so nothing has to decode until it is asked to.
 */
@Composable
public fun VideoAttachmentTile(
    attachment: Attachment,
    state: AttachmentPlaybackState,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    started: Boolean = false,
    onExpand: (() -> Unit)? = null,
    surface: @Composable (Modifier) -> Unit = {},
) {
    val colors = MiraTheme.colors
    val duration = state.durationMs.takeIf { it > 0 } ?: attachment.durationMs

    Column(modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(MiraRadii.media))
                .background(Color.Black)
                .clickable(enabled = !state.preparing, onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            if (started) {
                surface(Modifier.fillMaxWidth().fillMaxHeight())
            }
            if (state.preparing) {
                Text("Loading recording…", color = Color.White, style = MiraTheme.type.meta)
            }
            if (!state.playing && !state.preparing) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(colors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    MiraIcon(MiraIcons.Play, tint = colors.onAccent, size = 22.dp)
                }
            }
            onExpand?.let { expand ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(MiraSpacing.sm)
                        .size(34.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .clickable(onClickLabel = "Play full screen", onClick = expand),
                    contentAlignment = Alignment.Center,
                ) {
                    MiraIcon(MiraIcons.Expand, tint = Color.White, size = 16.dp)
                }
            }
        }

        Spacer(Modifier.height(MiraSpacing.sm))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Scrubber(progress = state.progress, onSeek = onSeek)
            }
            Spacer(Modifier.width(MiraSpacing.md))
            Text(
                text = "${formatDuration(state.positionMs)} / ${formatDuration(duration)}",
                style = MiraTheme.type.meta,
                color = colors.inkMuted,
            )
            if (!attachment.isUploaded) {
                Spacer(Modifier.width(MiraSpacing.sm))
                MiraIcon(MiraIcons.CloudPending, tint = colors.accent, size = 16.dp)
            }
        }

        state.errorMessage?.let { message ->
            Spacer(Modifier.height(MiraSpacing.xs))
            Text(message, style = MiraTheme.type.meta, color = colors.error)
        }
    }
}

/** A hairline track with an accent fill. Drag or tap anywhere on it to seek. */
@Composable
private fun Scrubber(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors
    var width by remember { mutableStateOf(1) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // The visual track is 3dp but the hit area is a comfortable 24dp.
            .height(24.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onSeek((change.position.x / width).coerceIn(0f, 1f))
                }
            }
            .clickable(indication = null, interactionSource = null) { },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(MiraRadii.pill))
                .background(colors.outline),
        )
        Box(
            Modifier
                .fillMaxWidth(progress)
                .height(3.dp)
                .clip(RoundedCornerShape(MiraRadii.pill))
                .background(colors.accent),
        )
    }
}

/** Icon for an attachment kind, used where a full player would be too much. */
@Composable
public fun AttachmentChip(attachment: Attachment, modifier: Modifier = Modifier) {
    val colors = MiraTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MiraRadii.pill))
            .background(colors.surfaceRaised)
            .padding(horizontal = MiraSpacing.md, vertical = MiraSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
    ) {
        MiraIcon(
            painter = when (attachment.kind) {
                AttachmentKind.AUDIO -> MiraIcons.Microphone
                AttachmentKind.VIDEO -> MiraIcons.Video
            },
            tint = colors.inkMuted,
            size = 14.dp,
        )
        Text(
            text = formatDuration(attachment.durationMs),
            style = MiraTheme.type.meta,
            color = colors.inkMuted,
        )
    }
}
