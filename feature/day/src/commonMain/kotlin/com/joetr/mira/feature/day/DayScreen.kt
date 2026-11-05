package com.joetr.mira.feature.day

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.media.AttachmentImage
import com.joetr.mira.media.AttachmentImageState
import com.joetr.mira.media.MediaSource
import com.joetr.mira.ui.components.CircleIconButton
import com.joetr.mira.ui.components.DayHeader
import com.joetr.mira.ui.components.EmptyState
import com.joetr.mira.ui.components.EntryCard
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.icons.MiraIcon
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.motion.sharedDayTitle
import com.joetr.mira.ui.motion.sharedEntryCard
import com.joetr.mira.ui.theme.MiraMotion
import com.joetr.mira.ui.theme.MiraRadii
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme

/**
 * Every entry for one day.
 *
 * An empty day shows one quiet line and a lot of space. Most of this app's life is spent mostly
 * empty, so nothing-yet has to look composed rather than broken.
 */
@Composable
public fun DayScreen(
    date: LocalDateKey,
    entries: List<Entry>,
    onBack: () -> Unit,
    onEntryClick: (Entry) -> Unit,
    onCompose: () -> Unit,
    onMediaClick: (Entry) -> Unit = onEntryClick,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    inSheet: Boolean = false,
    thumbnailFor: suspend (Attachment) -> MediaSource? = { null },
) {
    val colors = MiraTheme.colors

    Box(modifier.fillMaxSize().background(colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(MiraSpacing.lg))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MiraSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(if (inSheet) MiraIcons.Close else MiraIcons.Back, if (inSheet) "Close day" else "Back", onClick = onBack)
                Box(Modifier.weight(1f)) {
                    DayHeader(date, modifier = Modifier.sharedDayTitle(date.value))
                }
                CircleIconButton(
                    icon = MiraIcons.Plus,
                    contentDescription = "New entry",
                    onClick = onCompose,
                    filled = true,
                )
            }

            Spacer(Modifier.height(MiraSpacing.sectionGap))

            if (loading || error != null) {
                Column(Modifier.padding(horizontal = MiraSpacing.gutter)) {
                    androidx.compose.material3.Text(error ?: "Loading entries…", color = colors.inkMuted)
                    if (error != null) androidx.compose.material3.TextButton(onClick = onRetry) {
                        androidx.compose.material3.Text("Try again")
                    }
                }
            } else if (entries.isEmpty()) {
                Column(Modifier.padding(horizontal = MiraSpacing.gutter)) {
                    SectionLabel("Entries")
                    EmptyState("Nothing yet.")
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = MiraSpacing.gutter,
                        end = MiraSpacing.gutter,
                        bottom = MiraSpacing.giant,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MiraSpacing.cardGap),
                ) {
                    item {
                        Column {
                            SectionLabel("Entries")
                            Spacer(Modifier.height(MiraSpacing.md))
                        }
                    }
                    items(entries, key = { it.id }) { entry ->
                        EntryCard(
                            entry = entry,
                            onClick = { onEntryClick(entry) },
                            media = if (entry.hasMedia) {
                                { EntryMediaPreview(entry, thumbnailFor) { onMediaClick(entry) } }
                            } else {
                                null
                            },
                            modifier = Modifier.sharedEntryCard(entry.id).let {
                                if (MiraTheme.motionEnabled) {
                                    it.animateItem(
                                        fadeInSpec = tween(MiraMotion.STANDARD_MS),
                                        placementSpec = tween(MiraMotion.STANDARD_MS),
                                        fadeOutSpec = tween(MiraMotion.QUICK_MS),
                                    )
                                } else {
                                    it
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private val MediaPreviewSize = 60.dp

/**
 * The media carried by one entry, stacked down the right edge of its card.
 *
 * Visual media is preferred over audio and capped at two tiles so a many-attachment entry still
 * reads as text with a preview rather than a gallery. Audio gets an icon tile because it has no
 * frame to show.
 */
@Composable
private fun EntryMediaPreview(
    entry: Entry,
    thumbnailFor: suspend (Attachment) -> MediaSource?,
    onClick: () -> Unit,
) {
    val ordered = entry.attachments.sortedBy { it.kind == AttachmentKind.AUDIO }
    val shown = ordered.take(2)
    val overflow = ordered.size - shown.size
    Column(
        verticalArrangement = Arrangement.spacedBy(MiraSpacing.xs),
        horizontalAlignment = Alignment.End,
    ) {
        shown.forEachIndexed { index, attachment ->
            MediaPreviewTile(
                attachment = attachment,
                thumbnailFor = thumbnailFor,
                extra = if (index == shown.lastIndex) overflow else 0,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun MediaPreviewTile(
    attachment: Attachment,
    thumbnailFor: suspend (Attachment) -> MediaSource?,
    extra: Int,
    onClick: () -> Unit,
) {
    val colors = MiraTheme.colors
    val description = when (attachment.kind) {
        AttachmentKind.IMAGE -> "Open photo"
        AttachmentKind.VIDEO -> "Open video"
        AttachmentKind.AUDIO -> "Open voice note"
    }
    Box(
        modifier = Modifier
            .size(MediaPreviewSize)
            .clip(RoundedCornerShape(MiraRadii.media))
            .background(colors.surfaceRaised)
            .clickable(onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (attachment.kind) {
            AttachmentKind.IMAGE -> {
                var hasImage by remember(attachment.id) { mutableStateOf(false) }
                AttachmentImage(
                    attachment = attachment,
                    sourceFor = thumbnailFor,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    cacheKey = "day-preview",
                    modifier = Modifier.matchParentSize(),
                    onState = { hasImage = it == AttachmentImageState.Success },
                )
                if (!hasImage) {
                    MiraIcon(MiraIcons.Photo, tint = colors.inkFaint, size = 20.dp)
                }
            }
            AttachmentKind.VIDEO -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(colors.accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        MiraIcon(MiraIcons.Play, tint = colors.onAccent, size = 14.dp)
                    }
                }
            }
            AttachmentKind.AUDIO -> {
                MiraIcon(MiraIcons.Microphone, tint = colors.accent, size = 20.dp)
            }
        }

        if (!attachment.isUploaded && extra == 0) {
            MiraIcon(
                painter = MiraIcons.CloudPending,
                tint = colors.accent,
                size = 12.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(MiraSpacing.xxs),
            )
        }

        if (extra > 0) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$extra",
                    style = MiraTheme.type.meta,
                    color = Color.White,
                )
            }
        }
    }
}

/** One shared modal presentation on phone, tablet, desktop and web. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
public fun DayEntriesSheet(
    date: LocalDateKey,
    entries: List<Entry>,
    onDismiss: () -> Unit,
    onEntryClick: (Entry) -> Unit,
    onCompose: () -> Unit,
    onMediaClick: (Entry) -> Unit = onEntryClick,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    thumbnailFor: suspend (Attachment) -> MediaSource? = { null },
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 720.dp,
        containerColor = MiraTheme.colors.canvas,
        contentColor = MiraTheme.colors.ink,
    ) {
        DayScreen(
            date = date,
            entries = entries,
            onBack = onDismiss,
            onEntryClick = onEntryClick,
            onMediaClick = onMediaClick,
            onCompose = onCompose,
            onPreviousDay = {},
            onNextDay = {},
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            loading = loading,
            error = error,
            onRetry = onRetry,
            inSheet = true,
            thumbnailFor = thumbnailFor,
        )
    }
}
