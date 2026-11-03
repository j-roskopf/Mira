package com.joetr.mira.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.joetr.mira.ui.components.TagChip
import com.joetr.mira.ui.icons.MiraIcon
import com.joetr.mira.ui.icons.MiraIconPainter
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme

/**
 * Tags for an entry, kept as quiet chrome below the writing.
 *
 * Existing tags are pills; a single `+ Tag` opens an inline field without taking the writer away
 * from the page. Recently used tags appear as suggestions while that field is open, so the common
 * case is one tap rather than typing the same word again.
 */
@Composable
internal fun TagEditor(
    tags: List<String>,
    recentTags: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val suggestions = remember(tags, recentTags) {
        recentTags
            .filterNot { recent -> tags.any { it.equals(recent, ignoreCase = true) } }
            .take(8)
    }

    fun commit() {
        val tag = draft.trim().trimStart('#').trim().take(32)
        if (tag.isNotEmpty()) onAddTag(tag)
        draft = ""
    }

    LaunchedEffect(editing) {
        if (editing) runCatching { focusRequester.requestFocus() }
    }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
        ) {
            tags.forEach { tag ->
                TagChip(text = "#$tag", onRemove = { onRemoveTag(tag) })
            }
            if (!editing) {
                AddTagChip(onClick = { editing = true })
            }
        }

        if (editing) {
            Spacer(Modifier.height(MiraSpacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceRaised, RoundedCornerShape(MiraRadii.pill))
                    .padding(start = MiraSpacing.lg, end = MiraSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiraIcon(MiraIcons.Tag, tint = colors.inkMuted, size = 15.dp)
                Spacer(Modifier.width(MiraSpacing.sm))
                Box(Modifier.weight(1f)) {
                    if (draft.isEmpty()) {
                        Text("Tag name", style = MiraTheme.type.meta, color = colors.inkFaint)
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { if (it.length <= 32) draft = it },
                        textStyle = MiraTheme.type.meta.copy(color = colors.ink),
                        cursorBrush = SolidColor(colors.accent),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = "Tag name" },
                        interactionSource = remember { MutableInteractionSource() },
                    )
                }
                TagFieldAction(
                    icon = MiraIcons.Check,
                    description = "Add tag",
                    onClick = { commit() },
                )
                TagFieldAction(
                    icon = MiraIcons.Close,
                    description = "Cancel tag",
                    onClick = {
                        editing = false
                        draft = ""
                    },
                )
            }

            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(MiraSpacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
                ) {
                    Text(
                        text = "RECENT",
                        style = MiraTheme.type.sectionLabel,
                        color = colors.inkFaint,
                    )
                    suggestions.forEach { tag ->
                        TagChip(
                            text = "#$tag",
                            onClick = {
                                onAddTag(tag)
                                draft = ""
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TagFieldAction(
    icon: MiraIconPainter,
    description: String,
    onClick: () -> Unit,
) {
    val colors = MiraTheme.colors
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        MiraIcon(painter = icon, tint = colors.inkMuted, size = 16.dp)
    }
}

@Composable
private fun AddTagChip(onClick: () -> Unit) {
    val colors = MiraTheme.colors
    Row(
        modifier = Modifier
            .background(colors.surfaceRaised, RoundedCornerShape(MiraRadii.pill))
            .clickable(onClickLabel = "Add tag", onClick = onClick)
            .semantics { contentDescription = "Add tag" }
            .padding(horizontal = MiraSpacing.lg, vertical = MiraSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiraSpacing.xs),
    ) {
        MiraIcon(MiraIcons.Tag, tint = colors.inkMuted, size = 13.dp)
        Text("Tag", style = MiraTheme.type.meta, color = colors.inkMuted)
    }
}
