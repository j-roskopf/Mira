package com.joetr.mira.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
    label: String = "Voice note",
    onRemove: (() -> Unit)? = null,
    infoAction: (@Composable () -> Unit)? = null,
) {
    val colors = MiraTheme.colors
    val duration = state.durationMs.takeIf { it > 0 } ?: attachment.durationMs

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiraRadii.card))
            .background(colors.surfaceRaised)
            .padding(MiraSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MiraSpacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiraSpacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(colors.accent, CircleShape)
                    .clickable(enabled = !state.preparing, onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                MiraIcon(
                    painter = if (state.playing) MiraIcons.Pause else MiraIcons.Play,
                    tint = colors.onAccent,
                    size = 18.dp,
                )
            }

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MiraTheme.type.uiBody,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (!attachment.isUploaded) {
                        MiraIcon(MiraIcons.CloudPending, tint = colors.accent, size = 16.dp)
                        Spacer(Modifier.width(MiraSpacing.xs))
                    }
                    Text(
                        text = if (state.positionMs > 0) {
                            "${formatDuration(state.positionMs)} / ${formatDuration(duration)}"
                        } else {
                            formatDuration(duration)
                        },
                        style = MiraTheme.type.meta,
                        color = colors.inkMuted,
                    )
                }
                Spacer(Modifier.height(MiraSpacing.xxs))
                Scrubber(progress = state.progress, onSeek = onSeek)
            }

            infoAction?.invoke()
            onRemove?.let { remove ->
                CircleIconButton(
                    icon = MiraIcons.Trash,
                    contentDescription = "Remove $label",
                    onClick = remove,
                )
            }
        }

        state.errorMessage?.let { message ->
            Text(message, style = MiraTheme.type.meta, color = colors.error)
        }
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
    poster: (@Composable (Modifier) -> Unit)? = null,
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
            } else {
                poster?.invoke(Modifier.fillMaxSize())
                poster?.let {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.28f),
                                ),
                            ),
                    )
                }
            }
            if (state.preparing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Loading recording…", color = Color.White, style = MiraTheme.type.meta)
                }
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
                        .align(Alignment.TopEnd)
                        .padding(MiraSpacing.sm)
                        .size(34.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .clickable(onClickLabel = "Play full screen", onClick = expand),
                    contentAlignment = Alignment.Center,
                ) {
                    MiraIcon(MiraIcons.Expand, tint = Color.White, size = 16.dp)
                }
            }
            if (started) {
                VideoTransportControls(
                    state = state,
                    durationMs = duration,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }

        state.errorMessage?.let { message ->
            Spacer(Modifier.height(MiraSpacing.xs))
            Text(message, style = MiraTheme.type.meta, color = colors.error)
        }
    }
}

/**
 * The play/pause and scrubbing bar laid over a video.
 *
 * White-on-scrim rather than the theme's ink colours, because it sits on top of the picture rather
 * than on a surface — accent-on-video disappears against the wrong frame.
 */
@Composable
public fun VideoTransportControls(
    state: AttachmentPlaybackState,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.72f),
                ),
            )
            .padding(horizontal = MiraSpacing.sm, vertical = MiraSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                .clickable(enabled = !state.preparing, onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            MiraIcon(
                painter = if (state.playing) MiraIcons.Pause else MiraIcons.Play,
                tint = Color.White,
                size = 18.dp,
            )
        }
        Box(Modifier.weight(1f)) {
            Scrubber(
                progress = state.progress,
                onSeek = onSeek,
                trackColor = Color.White.copy(alpha = 0.3f),
                fillColor = Color.White,
            )
        }
        Text(
            text = if (durationMs > 0) {
                "${formatDuration(state.positionMs)} / ${formatDuration(durationMs)}"
            } else {
                formatDuration(state.positionMs)
            },
            style = MiraTheme.type.meta,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}

/** A hairline track with an accent fill. Drag or tap anywhere on it to seek. */
@Composable
private fun Scrubber(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = MiraTheme.colors.outline,
    fillColor: Color = MiraTheme.colors.accent,
    enabled: Boolean = true,
) {
    var width by remember { mutableStateOf(1) }
    val seek: (Float) -> Unit = { fraction ->
        if (enabled) onSeek(fraction.coerceIn(0f, 1f))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // The visual track is 3dp but the hit area is a comfortable 24dp.
            .height(24.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // One gesture for both tap and drag: seek on the down event, then keep tracking
                // while the finger moves. Splitting them into two detectors makes the tap
                // detector swallow the drag.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    seek(down.position.x / width)
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes
                            .firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        seek(change.position.x / width)
                        change.consume()
                    }
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
                .background(trackColor),
        )
        Box(
            Modifier
                .fillMaxWidth(progress)
                .height(3.dp)
                .clip(RoundedCornerShape(MiraRadii.pill))
                .background(fillColor),
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
                AttachmentKind.IMAGE -> MiraIcons.Photo
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
