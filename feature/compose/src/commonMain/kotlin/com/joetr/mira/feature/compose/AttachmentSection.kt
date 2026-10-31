package com.joetr.mira.feature.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.media.CaptureKind
import com.joetr.mira.media.MediaSource
import com.joetr.mira.media.VideoSurface
import com.joetr.mira.media.createMediaPlayerController
import com.joetr.mira.ui.components.AttachmentPlaybackState
import com.joetr.mira.ui.components.AudioAttachmentRow
import com.joetr.mira.ui.components.CircleIconButton
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.components.VideoAttachmentTile
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.theme.MiraSpacing
import kotlinx.coroutines.launch

/**
 * Playable attachments on an entry.
 *
 * One controller is shared across the list, so starting a second recording stops the first —
 * nobody wants two voice notes talking over each other, and it keeps exactly one decode pipeline
 * alive at a time, which matters most on desktop where each one is an FFmpeg subprocess.
 */
@Composable
internal fun AttachmentSection(
    attachments: List<Attachment>,
    sourceFor: suspend (Attachment) -> MediaSource?,
    onRemove: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    val scope = rememberCoroutineScope()
    val controller = remember { createMediaPlayerController() }
    var activeId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    val playback by controller.state.collectAsState()

    fun toggle(attachment: Attachment) {
        scope.launch {
            if (activeId == attachment.id) {
                if (playback.playing) controller.pause() else controller.play()
                return@launch
            }
            controller.release()
            activeId = attachment.id
            val source = sourceFor(attachment)
            if (source == null) {
                activeId = null
                return@launch
            }
            controller.load(
                source = source,
                kind = if (attachment.kind == AttachmentKind.VIDEO) {
                    CaptureKind.VIDEO
                } else {
                    CaptureKind.AUDIO
                },
            )
            controller.play()
        }
    }

    Column(modifier.fillMaxWidth()) {
        SectionLabel(if (attachments.size == 1) "Attachment" else "Attachments")
        Spacer(Modifier.height(MiraSpacing.md))

        attachments.forEach { attachment ->
            val isActive = activeId == attachment.id
            val state = if (isActive) {
                AttachmentPlaybackState(
                    playing = playback.playing,
                    preparing = playback.preparing,
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    errorMessage = playback.errorMessage,
                )
            } else {
                AttachmentPlaybackState(durationMs = attachment.durationMs)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
            ) {
                Box(Modifier.weight(1f)) {
                    when (attachment.kind) {
                        AttachmentKind.AUDIO -> AudioAttachmentRow(
                            attachment = attachment,
                            state = state,
                            onPlayPause = { toggle(attachment) },
                            onSeek = { fraction ->
                                if (isActive) {
                                    scope.launch {
                                        controller.seekTo(
                                            (fraction * playback.durationMs).toLong(),
                                        )
                                    }
                                }
                            },
                        )

                        AttachmentKind.VIDEO -> VideoAttachmentTile(
                            attachment = attachment,
                            state = state,
                            started = isActive,
                            onPlayPause = { toggle(attachment) },
                            onSeek = { fraction ->
                                if (isActive) {
                                    scope.launch {
                                        controller.seekTo(
                                            (fraction * playback.durationMs).toLong(),
                                        )
                                    }
                                }
                            },
                            surface = { surfaceModifier ->
                                VideoSurface(controller, surfaceModifier)
                            },
                        )
                    }
                }

                CircleIconButton(
                    icon = MiraIcons.Trash,
                    contentDescription = "Remove attachment",
                    onClick = {
                        if (isActive) {
                            controller.release()
                            activeId = null
                        }
                        onRemove(attachment)
                    },
                )
            }

            Spacer(Modifier.height(MiraSpacing.md))
        }
    }
}
