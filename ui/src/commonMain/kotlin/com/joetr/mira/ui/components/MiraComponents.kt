package com.joetr.mira.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.ui.displayDate
import com.joetr.mira.ui.formatTimestamp
import com.joetr.mira.ui.icons.MiraIcon
import com.joetr.mira.ui.icons.MiraIconPainter
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.theme.MiraMotion
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSizes
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme
import com.joetr.mira.ui.weekdayUpper

/**
 * A tiny uppercase, widely tracked section label.
 *
 * These plus [MiraSpacing.sectionGap] are how Mira separates content. There are no dividers.
 */
@Composable
public fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MiraTheme.type.sectionLabel,
        color = MiraTheme.colors.inkMuted,
    )
}

/** A surface that floats on the canvas. No elevation, no border — lightness alone. */
@Composable
public fun MiraCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MiraTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiraRadii.card))
            .background(colors.surface)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(MiraSpacing.lg),
    ) {
        content()
    }
}

/**
 * One entry in a day list.
 *
 * Timestamp top-left, attachment dots top-right, Literata body below. Clamped to [maxLines] with a
 * trailing ellipsis rather than a hard cut.
 */
@Composable
public fun EntryCard(
    entry: Entry,
    modifier: Modifier = Modifier,
    maxLines: Int = 6,
    onClick: (() -> Unit)? = null,
) {
    val colors = MiraTheme.colors
    MiraCard(modifier = modifier, onClick = onClick) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTimestamp(entry.createdAt),
                    style = MiraTheme.type.timestamp,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.weight(1f))
                AttachmentDots(entry)
            }
            if (entry.body.isNotBlank()) {
                Spacer(Modifier.height(MiraSpacing.sm))
                Text(
                    text = entry.body.trim(),
                    style = MiraTheme.type.entryBody,
                    color = colors.ink,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Accent dots showing what an entry carries.
 *
 * An attachment that has not finished uploading renders hollow rather than filled — the entry row
 * syncs instantly while its media may still be catching up, and that is a state, not a fault.
 */
@Composable
public fun AttachmentDots(entry: Entry, modifier: Modifier = Modifier) {
    if (entry.attachments.isEmpty()) return
    val colors = MiraTheme.colors
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(MiraSpacing.xs)) {
        entry.attachments.take(4).forEach { attachment ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .let {
                        if (attachment.isUploaded) {
                            it.background(colors.accent, CircleShape)
                        } else {
                            it.border(1.dp, colors.accent, CircleShape)
                        }
                    },
            )
        }
    }
}

/**
 * One day in the calendar heatmap.
 *
 * [rampStep] of -1 means no entries, and draws no circle at all — only the numeral, faintly. That
 * absence is what makes a month you journalled read as a constellation instead of a grid.
 */
@Composable
public fun HeatmapDay(
    day: Int,
    rampStep: Int,
    isToday: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = MiraTheme.colors
    val fill = rampStep.takeIf { it >= 0 }?.let { colors.heatmap.getOrNull(it) }
    val textColor = when {
        isSelected -> colors.onAccent
        fill != null && rampStep >= 3 -> colors.onAccent
        fill != null -> colors.ink
        else -> colors.inkFaint
    }
    Box(
        modifier = modifier
            .sizeIn(minWidth = MiraSizes.touchTarget, minHeight = MiraSizes.touchTarget)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(MiraSizes.dayCircle)
                .let {
                    when {
                        isSelected -> it.background(colors.accent, CircleShape)
                        fill != null -> it.background(fill, CircleShape)
                        else -> it
                    }
                }
                .let { if (isToday && !isSelected) it.border(1.dp, colors.ink, CircleShape) else it },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = day.toString(),
                style = MiraTheme.type.dayNumeral,
                color = textColor,
            )
        }
    }
}

/** `TUESDAY` over `30 OCT 2026`, centred. */
@Composable
public fun DayHeader(date: LocalDateKey, modifier: Modifier = Modifier) {
    val colors = MiraTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(date.weekdayUpper(), style = MiraTheme.type.dayTitle, color = colors.ink)
        Spacer(Modifier.height(MiraSpacing.xs))
        Text(
            date.displayDate().uppercase(),
            style = MiraTheme.type.dayDate,
            color = colors.inkMuted,
        )
    }
}

@Composable
public fun CircleIconButton(
    icon: MiraIconPainter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    filled: Boolean = false,
) {
    val colors = MiraTheme.colors
    Box(
        modifier = modifier
            .sizeIn(minWidth = MiraSizes.touchTarget, minHeight = MiraSizes.touchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(if (filled) colors.accent else colors.surfaceRaised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MiraIcon(
                painter = icon,
                tint = tint ?: if (filled) colors.onAccent else colors.ink,
                size = 18.dp,
                modifier = Modifier.semantics { this.contentDescription = contentDescription },
            )
        }
    }
}

/**
 * The record button.
 *
 * While recording it pulses continuously — that pulse *is* the recording indicator, so there is no
 * separate blinking dot anywhere in the capture UI.
 */
@Composable
public fun RecordButton(
    recording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: MiraIconPainter = MiraIcons.Microphone,
) {
    val colors = MiraTheme.colors
    val scale = if (recording && MiraTheme.motionEnabled) {
        val transition = rememberInfiniteTransition(label = "record-pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(MiraMotion.RECORD_PULSE_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "record-pulse-scale",
        ).value
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(64.dp)
            .scale(scale)
            .background(colors.accent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MiraIcon(
            painter = if (recording) MiraIcons.Pause else icon,
            tint = colors.onAccent,
            size = 26.dp,
        )
    }
}

@Composable
public fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = MiraTheme.colors
    val background = when {
        !enabled -> colors.surfaceRaised
        filled -> colors.accent
        else -> colors.surfaceRaised
    }
    val content = when {
        !enabled -> colors.inkFaint
        filled -> colors.onAccent
        else -> colors.ink
    }
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(MiraRadii.pill))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = MiraSpacing.xxl, vertical = MiraSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MiraTheme.type.button, color = content)
    }
}

@Composable
public fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors
    Box(
        modifier = modifier
            .background(
                if (selected) colors.accent else colors.surfaceRaised,
                RoundedCornerShape(MiraRadii.pill),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = MiraSpacing.lg, vertical = MiraSpacing.sm),
    ) {
        Text(
            text,
            style = MiraTheme.type.meta,
            color = if (selected) colors.onAccent else colors.inkMuted,
        )
    }
}

@Composable
public fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search entries",
) {
    val colors = MiraTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(MiraRadii.control))
            .padding(horizontal = MiraSpacing.md, vertical = MiraSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiraIcon(MiraIcons.Search, tint = colors.inkMuted, size = 18.dp)
        Spacer(Modifier.width(MiraSpacing.sm))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MiraTheme.type.meta, color = colors.inkFaint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MiraTheme.type.meta.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                interactionSource = remember { MutableInteractionSource() },
            )
        }
        if (value.isNotEmpty()) {
            CircleIconButton(
                icon = MiraIcons.Close,
                contentDescription = "Clear search",
                onClick = { onValueChange("") },
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * The empty state. One quiet line, generous space, no illustration and no button.
 *
 * Most of this app's life is spent mostly empty, so an empty day has to look composed rather than
 * broken.
 */
@Composable
public fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(vertical = MiraSpacing.xxl),
        style = MiraTheme.type.entryBody,
        color = MiraTheme.colors.inkFaint,
    )
}

/** Sync state, shown as a cloud glyph rather than a badge with a word in it. */
@Composable
public fun SyncIndicator(pendingCount: Int, modifier: Modifier = Modifier) {
    val colors = MiraTheme.colors
    val synced = pendingCount == 0
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        MiraIcon(
            painter = if (synced) MiraIcons.CloudDone else MiraIcons.CloudPending,
            tint = if (synced) colors.inkMuted else colors.accent,
            size = 18.dp,
        )
        if (!synced) {
            Spacer(Modifier.width(MiraSpacing.xs))
            Text("$pendingCount", style = MiraTheme.type.meta, color = colors.accent)
        }
    }
}
