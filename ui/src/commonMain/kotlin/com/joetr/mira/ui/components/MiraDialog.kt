package com.joetr.mira.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSpacing

@Composable
public fun MiraConfirmDialog(
    onDismissRequest: () -> Unit,
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String = "Cancel",
    destructive: Boolean = false,
) {
    MiraAlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        dismissButton = {
            DialogActionButton(text = dismissText, onClick = onDismissRequest)
        },
        confirmButton = {
            DialogActionButton(
                text = confirmText,
                onClick = onConfirm,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
public fun MiraAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(MiraRadii.sheet))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = MiraSpacing.xl, vertical = MiraSpacing.xl),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MiraSpacing.md))
            content()
            Spacer(Modifier.height(MiraSpacing.xl))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

@Composable
public fun DialogActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(MiraRadii.chip))
            .clickable(onClick = onClick)
            .padding(horizontal = MiraSpacing.md, vertical = MiraSpacing.sm),
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

@Composable
public fun DialogActionItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiraRadii.field))
            .clickable(onClick = onClick)
            .padding(vertical = MiraSpacing.md, horizontal = MiraSpacing.sm),
        style = MaterialTheme.typography.bodyLarge,
        color = color,
    )
}
