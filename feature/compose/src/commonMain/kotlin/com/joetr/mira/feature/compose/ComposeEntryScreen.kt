package com.joetr.mira.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.media.CameraFacing
import com.joetr.mira.media.CameraPreview
import com.joetr.mira.media.CaptureKind
import com.joetr.mira.media.CaptureLimits
import com.joetr.mira.media.CaptureResult
import com.joetr.mira.media.MediaRecorderController
import com.joetr.mira.media.MediaSource
import com.joetr.mira.media.RecordingState
import com.joetr.mira.media.VideoCaptureSettings
import com.joetr.mira.media.VideoQuality
import com.joetr.mira.media.supportsCapture
import com.joetr.mira.ui.components.CircleIconButton
import com.joetr.mira.ui.components.PillButton
import com.joetr.mira.ui.components.RecordButton
import com.joetr.mira.ui.displayDate
import com.joetr.mira.ui.formatDuration
import com.joetr.mira.ui.icons.MiraIcon
import com.joetr.mira.ui.icons.MiraIconPainter
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme
import kotlinx.coroutines.launch

/**
 * Writing an entry.
 *
 * One screen for all three kinds, because there is only one kind of entry: text that may carry
 * attachments. The three affordances at the bottom choose what to add, not what the entry *is*.
 * Capture expands in place rather than pushing a new screen, so you never lose the text you already
 * typed.
 */
@Composable
public fun ComposeEntryScreen(
    date: LocalDateKey,
    initialBody: String,
    recorder: MediaRecorderController,
    attachments: List<Attachment>,
    sourceFor: suspend (Attachment) -> MediaSource?,
    onRemoveAttachment: (Attachment) -> Unit,
    onBodyChange: (String) -> Unit,
    onDateClick: () -> Unit,
    onCaptureFinished: (CaptureResult) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialCapture: CaptureKind? = null,
    onDelete: (() -> Unit)? = null,
    saving: Boolean = false,
    errorMessage: String? = null,
) {
    val colors = MiraTheme.colors
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val body = initialBody
    var activeCapture by remember { mutableStateOf(initialCapture) }
    var previewExpanded by remember { mutableStateOf(false) }
    var videoSettings by remember { mutableStateOf(VideoCaptureSettings()) }
    val configurableVideo = recorder.supportsVideoSettings

    val state by recorder.state.collectAsState()

    LaunchedEffect(Unit) {
        if (initialCapture == null) {
            runCatching { focusRequester.requestFocus() }
        } else if (initialCapture != CaptureKind.VIDEO || !configurableVideo) {
            recorder.start(initialCapture)
        }
    }

    LaunchedEffect(videoSettings) {
        if (configurableVideo) recorder.configureVideo(videoSettings)
    }

    LaunchedEffect(state) {
        val finished = state as? RecordingState.Finished ?: return@LaunchedEffect
        onCaptureFinished(finished.result)
        activeCapture = null
    }

    LaunchedEffect(activeCapture) {
        if (activeCapture != CaptureKind.VIDEO) previewExpanded = false
    }

    fun startCapture(kind: CaptureKind) {
        if (kind == CaptureKind.VIDEO && configurableVideo && activeCapture != kind) {
            activeCapture = kind
            return
        }
        activeCapture = kind
        scope.launch {
            val currentState = recorder.state.value
            if (currentState == RecordingState.Preparing ||
                currentState == RecordingState.Finalizing ||
                currentState is RecordingState.Recording
            ) {
                return@launch
            }
            if (kind == CaptureKind.VIDEO && configurableVideo) recorder.configureVideo(videoSettings)
            recorder.start(kind)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .padding(horizontal = MiraSpacing.gutter),
    ) {
        Spacer(Modifier.height(MiraSpacing.lg))

        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(MiraIcons.Close, "Cancel", onClick = onCancel)
            Spacer(Modifier.weight(1f))
            Text(
                text = date.displayDate().uppercase(),
                style = MiraTheme.type.dayDate,
                color = colors.inkMuted,
                modifier = Modifier
                    .clickable(onClick = onDateClick)
                    .padding(MiraSpacing.sm),
            )
            // Only an entry that already exists can be deleted.
            onDelete?.let { delete ->
                CircleIconButton(MiraIcons.Trash, "Delete entry", onClick = delete)
            }
            Spacer(Modifier.weight(1f))
            PillButton(
                text = if (saving) "Saving…" else if (onDelete != null) "Save changes" else "Save",
                onClick = onSave,
                enabled = !saving && state != RecordingState.Finalizing && (body.isNotBlank() || attachments.isNotEmpty()),
            )
        }

        Spacer(Modifier.height(MiraSpacing.xl))

        errorMessage?.let {
            Text(it, style = MiraTheme.type.uiBody, color = colors.ink)
            Spacer(Modifier.height(MiraSpacing.sm))
        }

        Box(Modifier.weight(1f)) {
            if (body.isEmpty()) {
                Text("Write…", style = MiraTheme.type.entryBody, color = colors.inkFaint)
            }
            BasicTextField(
                value = body,
                onValueChange = onBodyChange,
                textStyle = MiraTheme.type.entryBody.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester),
                interactionSource = remember { MutableInteractionSource() },
            )
        }

        if (attachments.isNotEmpty()) {
            Spacer(Modifier.height(MiraSpacing.xl))
            AttachmentSection(
                attachments = attachments,
                sourceFor = sourceFor,
                onRemove = onRemoveAttachment,
            )
        }

        if (activeCapture == CaptureKind.VIDEO) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (configurableVideo) Modifier.weight(2f) else Modifier.aspectRatio(16f / 9f))
                    .clip(RoundedCornerShape(MiraRadii.media)),
            ) {
                // Only one preview surface is alive at a time; the full-screen dialog owns the
                // camera while it is open. Re-composing this one on close also gives CameraX a
                // fresh view to apply the current rotation to.
                if (!previewExpanded) {
                    CameraPreview(
                        controller = recorder,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClickLabel = "Expand camera preview") { previewExpanded = true },
                    )
                }
                if (configurableVideo) {
                    PreviewSettings(
                        settings = videoSettings,
                        qualities = recorder.supportedQualities,
                        showCameraFacing = recorder.supportsCameraFacing,
                        enabled = state != RecordingState.Preparing &&
                            state != RecordingState.Finalizing &&
                            state !is RecordingState.Recording,
                        onChange = { videoSettings = it },
                        compact = true,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(MiraSpacing.sm),
                    )
                }
            }
            Spacer(Modifier.height(MiraSpacing.md))
        }

        CaptureBar(
            state = state,
            activeCapture = activeCapture,
            onStart = { kind -> startCapture(kind) },
            onStop = { scope.launch { recorder.stop() } },
            onCancelCapture = {
                recorder.cancel()
                activeCapture = null
            },
        )

        Spacer(Modifier.height(MiraSpacing.xxl))
    }

    if (previewExpanded && activeCapture == CaptureKind.VIDEO) {
        FullScreenCameraPreview(
            controller = recorder,
            settings = videoSettings,
            qualities = if (configurableVideo) recorder.supportedQualities else emptyList(),
            showCameraFacing = configurableVideo && recorder.supportsCameraFacing,
            controlsEnabled = state != RecordingState.Preparing &&
                state != RecordingState.Finalizing &&
                state !is RecordingState.Recording,
            onSettingsChange = { videoSettings = it },
            recordingState = state,
            onToggleRecording = {
                if (state is RecordingState.Recording) {
                    scope.launch { recorder.stop() }
                } else {
                    startCapture(CaptureKind.VIDEO)
                }
            },
            onDismiss = { previewExpanded = false },
        )
    }
}

/** A modal, edge-to-edge view of the camera for framing a shot. */
@Composable
private fun FullScreenCameraPreview(
    controller: MediaRecorderController,
    settings: VideoCaptureSettings,
    qualities: List<VideoQuality>,
    showCameraFacing: Boolean,
    controlsEnabled: Boolean,
    onSettingsChange: (VideoCaptureSettings) -> Unit,
    recordingState: RecordingState,
    onToggleRecording: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MiraTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            CameraPreview(
                controller = controller,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClickLabel = "Close full screen preview", onClick = onDismiss),
            )
            CircleIconButton(
                icon = MiraIcons.Close,
                contentDescription = "Close full screen preview",
                onClick = onDismiss,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(MiraSpacing.lg),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .navigationBarsPadding()
                    .padding(MiraSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
            ) {
                PreviewSettings(
                    settings = settings,
                    qualities = qualities,
                    showCameraFacing = showCameraFacing,
                    enabled = controlsEnabled,
                    onChange = onSettingsChange,
                    compact = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(MiraSpacing.xs))
                if (recordingState == RecordingState.Preparing ||
                    recordingState == RecordingState.Finalizing
                ) {
                    CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(64.dp))
                } else {
                    RecordButton(
                        recording = recordingState is RecordingState.Recording,
                        onClick = onToggleRecording,
                        icon = MiraIcons.Video,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewSettings(
    settings: VideoCaptureSettings,
    qualities: List<VideoQuality>,
    showCameraFacing: Boolean,
    enabled: Boolean,
    onChange: (VideoCaptureSettings) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = if (compact) MiraSpacing.xs else MiraSpacing.sm
    val buttonSize = if (compact) 34.dp else 44.dp
    val iconSize = if (compact) 16.dp else 20.dp
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (qualities.isNotEmpty() || showCameraFacing) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                qualities.forEach { quality ->
                    PreviewChoice(
                        icon = quality.previewIcon(),
                        contentDescription = "Quality: ${quality.label}",
                        selected = settings.quality == quality,
                        enabled = enabled,
                        size = buttonSize,
                        iconSize = iconSize,
                        onClick = { onChange(settings.copy(quality = quality)) },
                    )
                }
                if (showCameraFacing) {
                    PreviewChoice(
                        icon = MiraIcons.Camera,
                        contentDescription = "Back camera",
                        selected = settings.facing == CameraFacing.BACK,
                        enabled = enabled,
                        size = buttonSize,
                        iconSize = iconSize,
                        onClick = { onChange(settings.copy(facing = CameraFacing.BACK)) },
                    )
                    PreviewChoice(
                        icon = MiraIcons.CameraFront,
                        contentDescription = "Front camera",
                        selected = settings.facing == CameraFacing.FRONT,
                        enabled = enabled,
                        size = buttonSize,
                        iconSize = iconSize,
                        onClick = { onChange(settings.copy(facing = CameraFacing.FRONT)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewChoice(
    icon: MiraIconPainter,
    contentDescription: String,
    selected: Boolean,
    enabled: Boolean,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val colors = MiraTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (selected) colors.accent else Color.White.copy(alpha = 0.16f))
            .clickable(enabled = enabled, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        MiraIcon(
            painter = icon,
            tint = if (selected) colors.onAccent else Color.White,
            size = iconSize,
        )
    }
}

private fun VideoQuality.previewIcon(): MiraIconPainter = when (this) {
    VideoQuality.STANDARD -> MiraIcons.QualityStandard
    VideoQuality.HIGH -> MiraIcons.QualityHigh
}

@Composable
private fun CaptureBar(
    state: RecordingState,
    activeCapture: CaptureKind?,
    onStart: (CaptureKind) -> Unit,
    onStop: () -> Unit,
    onCancelCapture: () -> Unit,
) {
    val colors = MiraTheme.colors
    val recording = state as? RecordingState.Recording

    Column(Modifier.fillMaxWidth()) {
        if (state == RecordingState.Preparing || state == RecordingState.Finalizing) {
            Text(
                text = if (state == RecordingState.Finalizing) "Finishing recording…"
                    else if (activeCapture == CaptureKind.VIDEO) "Starting camera and microphone…" else "Starting microphone…",
                style = MiraTheme.type.meta,
                color = colors.inkMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(MiraSpacing.sm))
        }
        if (recording != null) {
            val nearLimit = recording.elapsedMs >= CaptureLimits.WARN_DURATION_MS
            Text(
                text = formatDuration(recording.elapsedMs),
                style = MiraTheme.type.timestamp,
                color = if (nearLimit) colors.error else colors.accent,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(MiraSpacing.sm))
        }

        (state as? RecordingState.Failed)?.let { failure ->
            Text(
                text = failure.message,
                style = MiraTheme.type.meta,
                color = colors.error,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(MiraSpacing.sm))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording != null || activeCapture != null) {
                // The discard button must not shift the record button off centre.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircleIconButton(
                        icon = MiraIcons.Close,
                        contentDescription = "Discard recording",
                        onClick = onCancelCapture,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    if (state == RecordingState.Preparing || state == RecordingState.Finalizing) {
                        CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(64.dp))
                    } else RecordButton(
                        recording = recording != null,
                        onClick = {
                            if (recording != null) onStop() else activeCapture?.let(onStart)
                        },
                        icon = if (activeCapture == CaptureKind.VIDEO) {
                            MiraIcons.Video
                        } else {
                            MiraIcons.Microphone
                        },
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.xxl)) {
                    if (supportsCapture(CaptureKind.AUDIO)) {
                        CircleIconButton(
                            icon = MiraIcons.Microphone,
                            contentDescription = "Record audio",
                            onClick = { onStart(CaptureKind.AUDIO) },
                        )
                    }
                    if (supportsCapture(CaptureKind.VIDEO)) {
                        CircleIconButton(
                            icon = MiraIcons.Video,
                            contentDescription = "Record video",
                            onClick = { onStart(CaptureKind.VIDEO) },
                        )
                    }
                }
            }
        }
    }
}
