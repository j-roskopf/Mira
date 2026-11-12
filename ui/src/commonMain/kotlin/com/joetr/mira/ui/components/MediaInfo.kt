package com.joetr.mira.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.AttachmentState
import com.joetr.mira.ui.formatBytes
import com.joetr.mira.ui.formatDuration
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.theme.MiraSpacing
import kotlinx.coroutines.CancellationException

/**
 * An info affordance for one piece of media, opening a small sheet of its metadata.
 *
 * [resolveLocation] is optional because finding out whether the bytes are local, remote or in
 * memory is a suspend lookup; it runs when the info is opened rather than on every composition.
 */
@Composable
public fun MediaInfoButton(
    attachment: Attachment,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    capturedLabel: String? = null,
    resolveLocation: (suspend () -> String?)? = null,
) {
    var open by remember(attachment.id) { mutableStateOf(false) }
    var location by remember(attachment.id) { mutableStateOf<String?>(null) }
    var resolving by remember(attachment.id) { mutableStateOf(false) }

    CircleIconButton(
        icon = MiraIcons.Info,
        contentDescription = "About this ${attachment.kind.infoLabel().lowercase()}",
        onClick = { open = true },
        modifier = modifier,
        tint = tint,
    )

    if (open) {
        LaunchedEffect(attachment.id, open) {
            if (resolveLocation != null && location == null && !resolving) {
                resolving = true
                try {
                    location = resolveLocation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    location = null
                } finally {
                    resolving = false
                }
            }
        }
        MiraAlertDialog(
            onDismissRequest = { open = false },
            title = "${attachment.kind.infoLabel()} details",
            confirmButton = {
                DialogActionButton(text = "Done", onClick = { open = false })
            },
        ) {
            MediaInfoContent(
                attachment = attachment,
                capturedLabel = capturedLabel,
                location = location,
                resolving = resolving,
            )
        }
    }
}

@Composable
private fun MediaInfoContent(
    attachment: Attachment,
    capturedLabel: String?,
    location: String?,
    resolving: Boolean,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    MediaInfoRow("Type", attachment.kind.infoLabel(), muted)
    MediaInfoRow("Format", attachment.mime.ifBlank { "Unknown" }, muted)
    MediaInfoRow(
        label = "Size",
        value = if (attachment.bytes > 0) formatBytes(attachment.bytes) else "Unknown",
        muted = muted,
    )
    if (attachment.durationMs > 0) {
        MediaInfoRow("Duration", formatDuration(attachment.durationMs), muted)
    }
    MediaInfoRow(
        label = "Location",
        value = when {
            resolving -> "Checking…"
            location != null -> location
            attachment.state == AttachmentState.UPLOADING -> "On another device"
            attachment.isUploaded -> "Unknown"
            else -> "Only on this device"
        },
        muted = muted,
    )
    MediaInfoRow(
        label = "Upload",
        value = when (attachment.state) {
            AttachmentState.LOCAL -> "Not uploaded yet"
            AttachmentState.UPLOADING -> "Waiting for the recording device"
            AttachmentState.UPLOADED -> "Uploaded"
        },
        muted = muted,
    )
    capturedLabel?.let { MediaInfoRow("Captured", it, muted) }
}

@Composable
private fun MediaInfoRow(label: String, value: String, muted: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            modifier = Modifier.widthIn(min = 84.dp),
        )
        Spacer(Modifier.width(MiraSpacing.md))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun AttachmentKind.infoLabel(): String = when (this) {
    AttachmentKind.AUDIO -> "Voice note"
    AttachmentKind.VIDEO -> "Video"
    AttachmentKind.IMAGE -> "Photo"
}
