package com.joetr.mira.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
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
import com.joetr.mira.media.pickMedia
import com.joetr.mira.media.VideoAspectRatio
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
    onMediaPicked: (List<CaptureResult>) -> Unit,
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
    var cameraOpen by remember {
        mutableStateOf(initialCapture == CaptureKind.VIDEO || initialCapture == CaptureKind.PHOTO)
    }
    var videoSettings by remember { mutableStateOf(VideoCaptureSettings()) }
    val configurableVideo = recorder.supportsVideoSettings

    val state by recorder.state.collectAsState()

    LaunchedEffect(Unit) {
        if (initialCapture == null) {
            runCatching { focusRequester.requestFocus() }
        } else if (initialCapture == CaptureKind.AUDIO) {
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
        cameraOpen = false
    }

    fun beginCapture(kind: CaptureKind) {
        activeCapture = kind
        when (kind) {
            // Audio stays inline; there is nothing to frame.
            CaptureKind.AUDIO -> scope.launch {
                val currentState = recorder.state.value
                if (currentState == RecordingState.Preparing ||
                    currentState == RecordingState.Finalizing ||
                    currentState is RecordingState.Recording
                ) {
                    return@launch
                }
                recorder.start(kind)
            }
            CaptureKind.VIDEO, CaptureKind.PHOTO -> cameraOpen = true
        }
    }

    /** Runs the shutter: starts capture, or stops the running take (also snapshots desktop photos). */
    fun shutter() {
        val kind = activeCapture ?: return
        scope.launch {
            val currentState = recorder.state.value
            if (currentState == RecordingState.Preparing ||
                currentState == RecordingState.Finalizing
            ) {
                return@launch
            }
            if (currentState is RecordingState.Recording) {
                recorder.stop()
                return@launch
            }
            if (kind == CaptureKind.VIDEO && configurableVideo) recorder.configureVideo(videoSettings)
            recorder.start(kind)
        }
    }

    fun closeCamera() {
        recorder.cancel()
        activeCapture = null
        cameraOpen = false
    }

    /** Apply a settings change immediately, so a live desktop preview reshapes without a restart. */
    fun updateVideoSettings(updated: VideoCaptureSettings) {
        videoSettings = updated
        if (configurableVideo) recorder.configureVideo(updated)
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

        CaptureBar(
            state = state,
            activeCapture = activeCapture,
            onStart = { kind -> beginCapture(kind) },
            onStop = { scope.launch { recorder.stop() } },
            onCancelCapture = {
                recorder.cancel()
                activeCapture = null
            },
            onAttachFromLibrary = { scope.launch { onMediaPicked(pickMedia()) } },
        )

        Spacer(Modifier.height(MiraSpacing.xxl))
    }

    val captureKind = activeCapture
    if (cameraOpen && (captureKind == CaptureKind.VIDEO || captureKind == CaptureKind.PHOTO)) {
        CameraCaptureScreen(
            kind = captureKind,
            controller = recorder,
            settings = videoSettings,
            qualities = if (configurableVideo) recorder.supportedQualities else emptyList(),
            aspectRatios = if (configurableVideo) recorder.supportedAspectRatios else emptyList(),
            showCameraFacing = configurableVideo && recorder.supportsCameraFacing,
            settingsEnabled = state != RecordingState.Preparing &&
                state != RecordingState.Finalizing &&
                state !is RecordingState.Recording,
            onSettingsChange = { updateVideoSettings(it) },
            recordingState = state,
            onShutter = { shutter() },
            onDismiss = { closeCamera() },
        )
    }
}

/** A full-screen camera page, so the live preview is never squeezed into the editor. */
@Composable
private fun CameraCaptureScreen(
    kind: CaptureKind,
    controller: MediaRecorderController,
    settings: VideoCaptureSettings,
    qualities: List<VideoQuality>,
    aspectRatios: List<VideoAspectRatio>,
    showCameraFacing: Boolean,
    settingsEnabled: Boolean,
    onSettingsChange: (VideoCaptureSettings) -> Unit,
    recordingState: RecordingState,
    onShutter: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MiraTheme.colors
    val recording = recordingState as? RecordingState.Recording
    val busy = recordingState == RecordingState.Preparing || recordingState == RecordingState.Finalizing

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
                modifier = Modifier.fillMaxSize(),
                settings = settings,
            )
            CircleIconButton(
                icon = MiraIcons.Close,
                contentDescription = "Close camera",
                onClick = onDismiss,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(MiraSpacing.lg),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
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
                if (recording != null) {
                    val nearLimit = recording.elapsedMs >= CaptureLimits.WARN_DURATION_MS
                    Text(
                        text = formatDuration(recording.elapsedMs),
                        style = MiraTheme.type.timestamp,
                        color = if (nearLimit) colors.error else Color.White,
                    )
                }
                if (recordingState == RecordingState.Preparing ||
                    recordingState == RecordingState.Finalizing
                ) {
                    Text(
                        text = when {
                            kind == CaptureKind.PHOTO &&
                                recordingState == RecordingState.Finalizing -> "Saving photo…"
                            kind == CaptureKind.PHOTO -> "Taking photo…"
                            recordingState == RecordingState.Finalizing -> "Finishing recording…"
                            else -> "Starting camera and microphone…"
                        },
                        style = MiraTheme.type.meta,
                        color = Color.White,
                    )
                }
                (recordingState as? RecordingState.Failed)?.let { failure ->
                    Text(
                        text = failure.message,
                        style = MiraTheme.type.meta,
                        color = colors.error,
                        textAlign = TextAlign.Center,
                    )
                }
                if (qualities.isNotEmpty() || aspectRatios.isNotEmpty() || showCameraFacing) {
                    PreviewSettings(
                        settings = settings,
                        qualities = qualities,
                        aspectRatios = aspectRatios,
                        showCameraFacing = showCameraFacing,
                        enabled = settingsEnabled,
                        onChange = onSettingsChange,
                        compact = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(MiraSpacing.xs))
                }
                if (busy) {
                    CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(64.dp))
                } else {
                    RecordButton(
                        recording = recording != null,
                        onClick = onShutter,
                        icon = if (kind == CaptureKind.VIDEO) MiraIcons.Video else MiraIcons.Camera,
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
    aspectRatios: List<VideoAspectRatio>,
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
        if (aspectRatios.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                aspectRatios.forEach { aspectRatio ->
                    AspectRatioChoice(
                        aspectRatio = aspectRatio,
                        selected = settings.aspectRatio == aspectRatio,
                        enabled = enabled,
                        size = buttonSize,
                        iconSize = iconSize,
                        onClick = { onChange(settings.copy(aspectRatio = aspectRatio)) },
                    )
                }
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AspectRatioChoice(
    aspectRatio: VideoAspectRatio,
    selected: Boolean,
    enabled: Boolean,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val tooltipState = rememberTooltipState(isPersistent = false)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(aspectRatio.explanation, style = MiraTheme.type.meta)
            }
        },
        state = tooltipState,
    ) {
        PreviewChoice(
            icon = aspectRatio.previewIcon(),
            contentDescription = "Shape: ${aspectRatio.label}",
            selected = selected,
            enabled = enabled,
            size = size,
            iconSize = iconSize,
            onClick = onClick,
        )
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

private fun VideoAspectRatio.previewIcon(): MiraIconPainter = when (this) {
    VideoAspectRatio.ORIGINAL -> MiraIcons.AspectOriginal
    VideoAspectRatio.LANDSCAPE -> MiraIcons.AspectLandscape
    VideoAspectRatio.PORTRAIT -> MiraIcons.AspectPortrait
    VideoAspectRatio.SQUARE -> MiraIcons.AspectSquare
}

private val VideoAspectRatio.explanation: String
    get() = when (this) {
        VideoAspectRatio.ORIGINAL -> "Camera original — use whatever shape the camera reports"
        VideoAspectRatio.LANDSCAPE -> "Landscape 16:9 — wide frame, the standard video shape"
        VideoAspectRatio.PORTRAIT -> "Portrait 9:16 — tall frame, like a phone held upright"
        VideoAspectRatio.SQUARE -> "Square 1:1 — equal width and height"
    }

@Composable
private fun CaptureBar(
    state: RecordingState,
    activeCapture: CaptureKind?,
    onStart: (CaptureKind) -> Unit,
    onStop: () -> Unit,
    onCancelCapture: () -> Unit,
    onAttachFromLibrary: () -> Unit,
) {
    val colors = MiraTheme.colors
    val recording = state as? RecordingState.Recording

    Column(Modifier.fillMaxWidth()) {
        if (state == RecordingState.Preparing || state == RecordingState.Finalizing) {
            Text(
                text = if (state == RecordingState.Finalizing) "Finishing recording…"
                    else when (activeCapture) {
                        CaptureKind.VIDEO -> "Starting camera and microphone…"
                        CaptureKind.PHOTO -> "Starting camera…"
                        else -> "Starting microphone…"
                    },
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
            if (recording != null || activeCapture == CaptureKind.AUDIO) {
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
                        icon = when (activeCapture) {
                            CaptureKind.VIDEO -> MiraIcons.Video
                            CaptureKind.PHOTO -> MiraIcons.Camera
                            else -> MiraIcons.Microphone
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
                    if (supportsCapture(CaptureKind.PHOTO)) {
                        CircleIconButton(
                            icon = MiraIcons.Camera,
                            contentDescription = "Take photo",
                            onClick = { onStart(CaptureKind.PHOTO) },
                        )
                    }
                    CircleIconButton(
                        icon = MiraIcons.Photo,
                        contentDescription = "Attach from library",
                        onClick = onAttachFromLibrary,
                    )
                }
            }
        }
    }
}
