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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
    onMediaPicked: (List<CaptureResult>) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialCapture: CaptureKind? = null,
    onDelete: (() -> Unit)? = null,
    saving: Boolean = false,
    deleting: Boolean = false,
    errorMessage: String? = null,
    tags: List<String> = emptyList(),
    recentTags: List<String> = emptyList(),
    onAddTag: (String) -> Unit = {},
    onRemoveTag: (String) -> Unit = {},
) {
    val colors = MiraTheme.colors
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val body = initialBody
    // Start with the caret after the last character so reopening an entry continues where it
    // left off instead of dropping the cursor at the beginning.
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(initialBody, selection = TextRange(initialBody.length)))
    }
    LaunchedEffect(initialBody) {
        if (fieldValue.text != initialBody) {
            fieldValue = TextFieldValue(initialBody, selection = TextRange(initialBody.length))
        }
    }
    var activeCapture by remember { mutableStateOf(initialCapture) }
    var cameraOpen by remember {
        mutableStateOf(initialCapture == CaptureKind.VIDEO || initialCapture == CaptureKind.PHOTO)
    }
    // Existing attachments start tucked away; a newly captured or picked one opens the shelf.
    var attachmentsExpanded by remember { mutableStateOf(false) }
    var videoSettings by remember { mutableStateOf(VideoCaptureSettings()) }
    val configurableVideo = recorder.supportsVideoSettings

    val state by recorder.state.collectAsState()
    // When the keyboard covers the capture dock's usual space, attachments and tags move into the
    // scrolling body instead of sitting in a fixed footer that the keyboard can push off-screen.
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

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
        attachmentsExpanded = true
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            EditorTopBar(
                date = date,
                saving = saving,
                deleting = deleting,
                editing = onDelete != null,
                canSave = !saving && !deleting && state != RecordingState.Finalizing &&
                    (body.isNotBlank() || attachments.isNotEmpty()),
                onCancel = onCancel,
                onDateClick = onDateClick,
                onDelete = onDelete,
                onSave = onSave,
            )
    
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MiraSpacing.gutter),
            ) {
                Spacer(Modifier.height(MiraSpacing.md))
    
                errorMessage?.let {
                    Text(it, style = MiraTheme.type.uiBody, color = colors.error)
                    Spacer(Modifier.height(MiraSpacing.md))
                }
    
                Box(
                    Modifier.fillMaxWidth(),
                ) {
                    if (body.isEmpty()) {
                        Text("Write…", style = MiraTheme.type.entryBody, color = colors.inkFaint)
                    }
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = {
                            fieldValue = it
                            onBodyChange(it.text)
                        },
                        textStyle = MiraTheme.type.entryBody.copy(color = colors.ink),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        interactionSource = remember { MutableInteractionSource() },
                    )
                }
    
                Spacer(Modifier.height(MiraSpacing.md))

                // With the keyboard up, the fixed footer below has nowhere to shrink to, so
                // attachments and tags fold into the same scroll as the text instead of risking
                // getting pushed behind the keyboard with no way to reach them.
                if (keyboardVisible) {
                    if (attachments.isNotEmpty()) {
                        AttachmentSection(
                            attachments = attachments,
                            sourceFor = sourceFor,
                            onRemove = onRemoveAttachment,
                            expanded = attachmentsExpanded,
                            onExpandedChange = { attachmentsExpanded = it },
                        )
                        Spacer(Modifier.height(MiraSpacing.sm))
                    }
                    TagEditor(
                        tags = tags,
                        recentTags = recentTags,
                        onAddTag = onAddTag,
                        onRemoveTag = onRemoveTag,
                        modifier = Modifier.padding(bottom = MiraSpacing.sm),
                    )
                }
            }

            // The attachment shelf is chrome, not page content: collapsed it is a single row directly
            // above the capture dock; expanded it grows upward from there. Only shown here while the
            // keyboard is down — see above for the keyboard-up case.
            if (!keyboardVisible) {
                if (attachments.isNotEmpty()) {
                    AttachmentSection(
                        attachments = attachments,
                        sourceFor = sourceFor,
                        onRemove = onRemoveAttachment,
                        expanded = attachmentsExpanded,
                        onExpandedChange = { attachmentsExpanded = it },
                        modifier = Modifier.padding(horizontal = MiraSpacing.gutter),
                    )
                }

                // Tags are editor chrome, not page content: pinned above the capture dock so they
                // never drift into the middle of the page on a short entry.
                TagEditor(
                    tags = tags,
                    recentTags = recentTags,
                    onAddTag = onAddTag,
                    onRemoveTag = onRemoveTag,
                    modifier = Modifier.padding(
                        start = MiraSpacing.gutter,
                        end = MiraSpacing.gutter,
                        top = MiraSpacing.sm,
                        bottom = MiraSpacing.sm,
                    ),
                )
            }

            CaptureDock(
                state = state,
                activeCapture = activeCapture,
                onStart = { kind -> beginCapture(kind) },
                onStop = { scope.launch { recorder.stop() } },
                onCancelCapture = {
                    recorder.cancel()
                    activeCapture = null
                },
                onAttachFromLibrary = {
                    scope.launch {
                        val picked = pickMedia()
                        if (picked.isNotEmpty()) attachmentsExpanded = true
                        onMediaPicked(picked)
                    }
                },
            )
        }

    if (deleting) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
            ) {
                CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(40.dp))
                Text("Deleting entry…", style = MiraTheme.type.meta, color = colors.ink)
            }
        }
    }
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

/**
 * Quiet chrome for the editor: close and delete on the left, the date in the middle, Save on the
 * right. The date is the only element that reads as a label, so the eye lands on the page, not
 * the toolbar.
 */
@Composable
private fun EditorTopBar(
    date: LocalDateKey,
    saving: Boolean,
    deleting: Boolean,
    editing: Boolean,
    canSave: Boolean,
    onCancel: () -> Unit,
    onDateClick: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: () -> Unit,
) {
    val colors = MiraTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MiraSpacing.sm, vertical = MiraSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(MiraIcons.Close, "Cancel", onClick = onCancel)
        onDelete?.let { delete ->
            Spacer(Modifier.width(MiraSpacing.xs))
            CircleIconButton(MiraIcons.Trash, "Delete entry", onClick = delete)
        }

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = date.displayDate().uppercase(),
                style = MiraTheme.type.dayDate,
                color = colors.inkMuted,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClick = onDateClick)
                    .padding(horizontal = MiraSpacing.sm, vertical = MiraSpacing.md),
            )
        }

        if (saving || deleting) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(colors.surfaceRaised, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = colors.inkMuted,
                    strokeWidth = 2.dp,
                )
            }
        } else {
            CircleIconButton(
                icon = MiraIcons.Check,
                contentDescription = if (editing) "Save changes" else "Save entry",
                onClick = onSave,
                filled = true,
                enabled = canSave,
            )
        }
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

/**
 * The capture dock: one calm row of labelled actions while idle, replaced in place by the
 * recording transport once capture starts.
 *
 * Labels matter here. Four anonymous glyphs at the bottom of an editor ask the user to decode
 * them; a quiet label under each icon does not add noise and removes the guesswork.
 */
@Composable
private fun CaptureDock(
    state: RecordingState,
    activeCapture: CaptureKind?,
    onStart: (CaptureKind) -> Unit,
    onStop: () -> Unit,
    onCancelCapture: () -> Unit,
    onAttachFromLibrary: () -> Unit,
) {
    val colors = MiraTheme.colors
    val recording = state as? RecordingState.Recording
    val busy = state == RecordingState.Preparing || state == RecordingState.Finalizing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                colors.surfaceRaised,
                RoundedCornerShape(topStart = MiraRadii.sheet, topEnd = MiraRadii.sheet),
            )
            .padding(horizontal = MiraSpacing.gutter)
            .padding(top = MiraSpacing.md, bottom = MiraSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) {
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

        if (recording != null || activeCapture == CaptureKind.AUDIO) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircleIconButton(
                    icon = MiraIcons.Close,
                    contentDescription = "Discard recording",
                    onClick = onCancelCapture,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                if (busy) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (supportsCapture(CaptureKind.AUDIO)) {
                    CaptureAction(
                        icon = MiraIcons.Microphone,
                        label = "Voice",
                        contentDescription = "Record audio",
                        onClick = { onStart(CaptureKind.AUDIO) },
                    )
                }
                if (supportsCapture(CaptureKind.VIDEO)) {
                    CaptureAction(
                        icon = MiraIcons.Video,
                        label = "Video",
                        contentDescription = "Record video",
                        onClick = { onStart(CaptureKind.VIDEO) },
                    )
                }
                if (supportsCapture(CaptureKind.PHOTO)) {
                    CaptureAction(
                        icon = MiraIcons.Camera,
                        label = "Photo",
                        contentDescription = "Take photo",
                        onClick = { onStart(CaptureKind.PHOTO) },
                    )
                }
                CaptureAction(
                    icon = MiraIcons.Photo,
                    label = "Library",
                    contentDescription = "Attach from library",
                    onClick = onAttachFromLibrary,
                )
            }
        }
    }
}

@Composable
private fun CaptureAction(
    icon: MiraIconPainter,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = MiraTheme.colors
    Column(
        modifier = Modifier
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(MiraRadii.control))
            .clickable(onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(vertical = MiraSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(colors.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MiraIcon(painter = icon, tint = colors.inkMuted, size = 20.dp)
        }
        Spacer(Modifier.height(MiraSpacing.sm))
        Text(label.uppercase(), style = MiraTheme.type.sectionLabel, color = colors.inkMuted)
    }
}
