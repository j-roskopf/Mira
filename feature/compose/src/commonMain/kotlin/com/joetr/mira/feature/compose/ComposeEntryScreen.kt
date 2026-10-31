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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.media.CameraPreview
import com.joetr.mira.media.CaptureKind
import com.joetr.mira.media.CaptureLimits
import com.joetr.mira.media.CaptureResult
import com.joetr.mira.media.MediaRecorderController
import com.joetr.mira.media.RecordingState
import com.joetr.mira.media.supportsCapture
import com.joetr.mira.ui.components.CircleIconButton
import com.joetr.mira.ui.components.PillButton
import com.joetr.mira.ui.components.RecordButton
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.displayDate
import com.joetr.mira.ui.formatDuration
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
    attachmentCount: Int,
    onBodyChange: (String) -> Unit,
    onDateClick: () -> Unit,
    onCaptureFinished: (CaptureResult) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialCapture: CaptureKind? = null,
    onDelete: (() -> Unit)? = null,
) {
    val colors = MiraTheme.colors
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var body by remember { mutableStateOf(initialBody) }
    var activeCapture by remember { mutableStateOf(initialCapture) }

    val state by recorder.state.collectAsState()

    LaunchedEffect(Unit) {
        if (initialCapture == null) {
            runCatching { focusRequester.requestFocus() }
        } else {
            recorder.start(initialCapture)
        }
    }

    LaunchedEffect(state) {
        val finished = state as? RecordingState.Finished ?: return@LaunchedEffect
        onCaptureFinished(finished.result)
        activeCapture = null
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
                text = "Save",
                onClick = onSave,
                enabled = body.isNotBlank() || attachmentCount > 0,
            )
        }

        Spacer(Modifier.height(MiraSpacing.xl))

        Box(Modifier.weight(1f)) {
            if (body.isEmpty()) {
                Text("Write…", style = MiraTheme.type.entryBody, color = colors.inkFaint)
            }
            BasicTextField(
                value = body,
                onValueChange = {
                    body = it
                    onBodyChange(it)
                },
                textStyle = MiraTheme.type.entryBody.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester),
                interactionSource = remember { MutableInteractionSource() },
            )
        }

        if (activeCapture == CaptureKind.VIDEO) {
            CameraPreview(
                controller = recorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(MiraRadii.media)),
            )
            Spacer(Modifier.height(MiraSpacing.md))
        }

        CaptureBar(
            state = state,
            activeCapture = activeCapture,
            attachmentCount = attachmentCount,
            onStart = { kind ->
                activeCapture = kind
                scope.launch { recorder.start(kind) }
            },
            onStop = { scope.launch { recorder.stop() } },
            onCancelCapture = {
                recorder.cancel()
                activeCapture = null
            },
        )

        Spacer(Modifier.height(MiraSpacing.xxl))
    }
}

@Composable
private fun CaptureBar(
    state: RecordingState,
    activeCapture: CaptureKind?,
    attachmentCount: Int,
    onStart: (CaptureKind) -> Unit,
    onStop: () -> Unit,
    onCancelCapture: () -> Unit,
) {
    val colors = MiraTheme.colors
    val recording = state as? RecordingState.Recording

    Column(Modifier.fillMaxWidth()) {
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

        if (attachmentCount > 0 && recording == null) {
            SectionLabel("$attachmentCount attached")
            Spacer(Modifier.height(MiraSpacing.sm))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording != null || activeCapture != null) {
                CircleIconButton(MiraIcons.Close, "Discard recording", onClick = onCancelCapture)
                Spacer(Modifier.weight(1f))
                RecordButton(
                    recording = recording != null,
                    onClick = onStop,
                    icon = if (activeCapture == CaptureKind.VIDEO) {
                        MiraIcons.Video
                    } else {
                        MiraIcons.Microphone
                    },
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(MiraSpacing.huge))
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
