package com.joetr.mira.feature.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.media.CaptureKind
import com.joetr.mira.media.MediaSource
import com.joetr.mira.media.VideoSurface
import com.joetr.mira.media.createMediaPlayerController
import com.joetr.mira.media.decodePhoto
import com.joetr.mira.media.readBytesForDisplay
import com.joetr.mira.ui.components.AttachmentPlaybackState
import com.joetr.mira.ui.components.AudioAttachmentRow
import com.joetr.mira.ui.components.CircleIconButton
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.components.VideoAttachmentTile
import com.joetr.mira.ui.formatDuration
import com.joetr.mira.ui.icons.MiraIcon
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.theme.MiraMotion
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Playable attachments for the entry being written, presented as a bottom sheet.
 *
 * One controller is shared across the list, so starting a second recording stops the first —
 * nobody wants two voice notes talking over each other, and it keeps exactly one decode pipeline
 * alive at a time, which matters most on desktop where each one is an FFmpeg subprocess.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet(
    attachments: List<Attachment>,
    sourceFor: suspend (Attachment) -> MediaSource?,
    onRemove: (Attachment) -> Unit,
    onDismiss: () -> Unit,
) {
    if (attachments.isEmpty()) return

    val colors = MiraTheme.colors
    // Keep the sheet from becoming a full-screen page on short windows; the list scrolls inside.
    val listHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * 0.6f).toDp()
    }
    val scope = rememberCoroutineScope()
    val controller = remember { createMediaPlayerController() }
    var activeId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    val playback by controller.state.collectAsState()

    fun toggle(attachment: Attachment) {
        if (loading) return
        scope.launch {
            if (activeId == attachment.id && sourceError == null && playback.errorMessage == null) {
                if (playback.playing) controller.pause() else controller.play()
                return@launch
            }
            controller.release()
            activeId = attachment.id
            sourceError = null
            loading = true
            try {
                val source = sourceFor(attachment)
                if (source == null) {
                    sourceError = "This recording is unavailable. Its local file may be missing and no cloud copy could be loaded."
                    return@launch
                }
                controller.load(
                    source = source,
                    kind = if (attachment.kind == AttachmentKind.VIDEO) CaptureKind.VIDEO else CaptureKind.AUDIO,
                )
                if (controller.state.value.errorMessage == null) controller.play()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                sourceError = "Could not open this recording. Please try again."
            } finally {
                loading = false
            }
        }
    }

    fun stateFor(attachment: Attachment): AttachmentPlaybackState =
        if (activeId == attachment.id) {
            AttachmentPlaybackState(
                playing = playback.playing,
                preparing = loading || playback.preparing,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                errorMessage = sourceError ?: playback.errorMessage,
                videoWidth = playback.videoWidth,
                videoHeight = playback.videoHeight,
            )
        } else {
            AttachmentPlaybackState(durationMs = attachment.durationMs)
        }

    fun seek(attachment: Attachment, fraction: Float) {
        if (activeId != attachment.id) return
        scope.launch {
            controller.seekTo((fraction * playback.durationMs).toLong())
        }
    }

    fun remove(attachment: Attachment) {
        if (activeId == attachment.id) {
            controller.release()
            activeId = null
        }
        onRemove(attachment)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 720.dp,
        containerColor = colors.canvas,
        contentColor = colors.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MiraSpacing.gutter)
                .padding(bottom = MiraSpacing.xxl),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Attachments · ${attachments.size}")
                Spacer(Modifier.weight(1f))
                Text(
                    text = attachmentSummary(attachments),
                    style = MiraTheme.type.meta,
                    color = colors.inkMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(MiraSpacing.md))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = listHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MiraSpacing.md),
            ) {
                attachments.forEachIndexed { index, attachment ->
                    key(attachment.id) {
                        AttachmentEntry(
                            attachment = attachment,
                            label = "${
                                when (attachment.kind) {
                                    AttachmentKind.VIDEO -> "Video"
                                    AttachmentKind.IMAGE -> "Photo"
                                    AttachmentKind.AUDIO -> "Voice note"
                                }
                            } ${index + 1}",
                            state = stateFor(attachment),
                            sourceFor = sourceFor,
                            started = activeId == attachment.id && expandedId != attachment.id,
                            onPlayPause = { toggle(attachment) },
                            onSeek = { fraction -> seek(attachment, fraction) },
                            onRemove = { remove(attachment) },
                            onExpand = {
                                if (activeId != attachment.id) toggle(attachment)
                                expandedId = attachment.id
                            },
                            surface = { surfaceModifier -> VideoSurface(controller, surfaceModifier) },
                        )
                    }
                }
            }
        }
    }

    val expandedVideo = attachments.firstOrNull { it.id == expandedId }
    if (expandedVideo != null && expandedVideo.kind == AttachmentKind.VIDEO) {
        val isActive = activeId == expandedVideo.id
        val state = if (isActive) {
            AttachmentPlaybackState(
                playing = playback.playing,
                preparing = loading || playback.preparing,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                errorMessage = sourceError ?: playback.errorMessage,
                videoWidth = playback.videoWidth,
                videoHeight = playback.videoHeight,
            )
        } else {
            AttachmentPlaybackState(durationMs = expandedVideo.durationMs)
        }
        FullScreenVideo(
            attachment = expandedVideo,
            state = state,
            onDismiss = { expandedId = null },
            onPlayPause = { toggle(expandedVideo) },
            surface = { surfaceModifier -> VideoSurface(controller, surfaceModifier) },
        )
    }
}

/** A one-line inventory for the sheet header: `1 video · 2 voice notes`. */
private fun attachmentSummary(attachments: List<Attachment>): String = buildList {
    val videos = attachments.count { it.kind == AttachmentKind.VIDEO }
    val photos = attachments.count { it.kind == AttachmentKind.IMAGE }
    val audio = attachments.count { it.kind == AttachmentKind.AUDIO }
    if (videos > 0) add(if (videos == 1) "1 video" else "$videos videos")
    if (photos > 0) add(if (photos == 1) "1 photo" else "$photos photos")
    if (audio > 0) add(if (audio == 1) "1 voice note" else "$audio voice notes")
}.joinToString(" · ")

@Composable
private fun AttachmentEntry(
    attachment: Attachment,
    label: String,
    state: AttachmentPlaybackState,
    started: Boolean,
    sourceFor: suspend (Attachment) -> MediaSource?,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onRemove: () -> Unit,
    onExpand: () -> Unit,
    surface: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (attachment.kind) {
        AttachmentKind.AUDIO -> AudioAttachmentRow(
            attachment = attachment,
            state = state,
            label = label,
            onPlayPause = onPlayPause,
            onSeek = onSeek,
            onRemove = onRemove,
            modifier = modifier,
        )

        AttachmentKind.VIDEO -> MediaAttachment(
            attachment = attachment,
            label = label,
            onRemove = onRemove,
            modifier = modifier,
        ) {
            VideoAttachmentTile(
                attachment = attachment,
                state = state,
                started = started,
                onPlayPause = onPlayPause,
                onSeek = onSeek,
                onExpand = onExpand,
                surface = surface,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AttachmentKind.IMAGE -> MediaAttachment(
            attachment = attachment,
            label = label,
            onRemove = onRemove,
            modifier = modifier,
        ) {
            ImageAttachmentTile(
                attachment = attachment,
                sourceFor = sourceFor,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A media attachment is its tile plus one quiet caption line. No outer card: the tile is already
 * the surface, and wrapping it again was most of what made a page of attachments feel heavy.
 */
@Composable
private fun MediaAttachment(
    attachment: Attachment,
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MiraTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
        ) {
            MiraIcon(
                painter = when (attachment.kind) {
                    AttachmentKind.VIDEO -> MiraIcons.Video
                    AttachmentKind.IMAGE -> MiraIcons.Photo
                    AttachmentKind.AUDIO -> MiraIcons.Microphone
                },
                tint = colors.inkMuted,
                size = 16.dp,
            )
            Text(
                text = label,
                style = MiraTheme.type.uiBody,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            if (attachment.kind == AttachmentKind.VIDEO) {
                Text(
                    text = formatDuration(attachment.durationMs),
                    style = MiraTheme.type.meta,
                    color = colors.inkMuted,
                )
            }
            if (!attachment.isUploaded) {
                MiraIcon(MiraIcons.CloudPending, tint = colors.accent, size = 16.dp)
            }
            CircleIconButton(
                icon = MiraIcons.Trash,
                contentDescription = "Remove $label",
                onClick = onRemove,
            )
        }
        Spacer(Modifier.height(MiraSpacing.sm))
        content()
    }
}

/**
 * A photo attachment: a static thumbnail, no scrubber — tap it to view full screen.
 *
 * Decoded through [decodePhoto] rather than a network image loader, since the
 * bytes already come from [sourceFor] exactly the way audio and video playback already resolve
 * them; a [MediaSource.Remote] (not yet cached locally) shows the same "unavailable" message
 * playback already shows in that case, rather than fetching over the network here too.
 *
 * Thumbnails are decoded to the on-screen tile size (and full-screen to the window) on a
 * background dispatcher — phone JPEGs are often 12 MP, and keeping those bitmaps in the
 * composition made Android entry open and attachment scrolling unusable.
 */
@Composable
private fun ImageAttachmentTile(
    attachment: Attachment,
    sourceFor: suspend (Attachment) -> MediaSource?,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors
    val density = LocalDensity.current
    // The sheet is capped at 720.dp wide; decode just enough pixels for that box at the current density.
    val thumbMaxPx = with(density) { 720.dp.roundToPx() }.coerceAtLeast(1)
    val window = LocalWindowInfo.current.containerSize
    val fullMaxPx = maxOf(window.width, window.height, thumbMaxPx).coerceAtMost(2048)

    var thumb by remember(attachment.id) { mutableStateOf<ImageBitmap?>(null) }
    var full by remember(attachment.id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(attachment.id) { mutableStateOf(false) }
    var expanded by remember(attachment.id) { mutableStateOf(false) }

    LaunchedEffect(attachment.id, thumbMaxPx) {
        thumb = null
        full = null
        failed = false
        val decoded = withContext(Dispatchers.Default) {
            val bytes = runCatching { sourceFor(attachment)?.readBytesForDisplay() }.getOrNull()
            bytes?.let { decodePhoto(it, thumbMaxPx) }
        }
        thumb = decoded
        failed = decoded == null
    }

    LaunchedEffect(expanded, attachment.id, fullMaxPx) {
        if (!expanded || full != null) return@LaunchedEffect
        full = withContext(Dispatchers.Default) {
            val bytes = runCatching { sourceFor(attachment)?.readBytesForDisplay() }.getOrNull()
            bytes?.let { decodePhoto(it, fullMaxPx) }
        } ?: thumb
    }

    Box(
        modifier = modifier
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(MiraRadii.media))
            .background(colors.surfaceSunken)
            .clickable(enabled = thumb != null, onClickLabel = "View photo") { expanded = true },
        contentAlignment = Alignment.Center,
    ) {
        val current = thumb
        when {
            current != null -> Image(
                bitmap = current,
                contentDescription = "Photo attachment",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MiraSpacing.xs),
            ) {
                MiraIcon(MiraIcons.Photo, tint = colors.inkFaint, size = 22.dp)
                Text("Photo unavailable", style = MiraTheme.type.meta, color = colors.inkMuted)
            }
            else -> CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(24.dp))
        }
    }

    if (expanded) {
        val shown = full ?: thumb
        if (shown != null) {
            FullScreenImage(bitmap = shown, onDismiss = { expanded = false })
        }
    }
}

@Composable
private fun FullScreenImage(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Photo attachment",
                modifier = Modifier.fillMaxSize().clickable(onClickLabel = "Close photo", onClick = onDismiss),
                contentScale = ContentScale.Fit,
            )
            CircleIconButton(
                icon = MiraIcons.Close,
                contentDescription = "Close photo",
                onClick = onDismiss,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(MiraSpacing.lg),
            )
        }
    }
}

@Composable
private fun FullScreenVideo(
    attachment: Attachment,
    state: AttachmentPlaybackState,
    onDismiss: () -> Unit,
    onPlayPause: () -> Unit,
    surface: @Composable (Modifier) -> Unit,
) {
    val colors = MiraTheme.colors
    val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) {
        state.videoWidth.toFloat() / state.videoHeight
    } else {
        null
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (ratio != null) {
                val fit = if (ratio >= 1f) {
                    Modifier.fillMaxWidth().aspectRatio(ratio)
                } else {
                    Modifier.fillMaxHeight().aspectRatio(ratio)
                }
                surface(fit)
            } else {
                surface(Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(onClickLabel = if (state.playing) "Pause" else "Play", onClick = onPlayPause),
            )
            if (!state.playing) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(colors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    MiraIcon(MiraIcons.Play, tint = colors.onAccent, size = 24.dp)
                }
            }
            CircleIconButton(
                icon = MiraIcons.Close,
                contentDescription = "Close full screen video",
                onClick = onDismiss,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(MiraSpacing.lg),
            )
        }
    }
}
