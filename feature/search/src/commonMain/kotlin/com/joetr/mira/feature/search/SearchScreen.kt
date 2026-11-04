package com.joetr.mira.feature.search

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.joetr.mira.data.entries.SearchFilters
import com.joetr.mira.domain.Entry
import com.joetr.mira.ui.components.AttachmentDots
import com.joetr.mira.ui.components.Chip
import com.joetr.mira.ui.components.EmptyState
import com.joetr.mira.ui.components.EntryTagLine
import com.joetr.mira.ui.components.SearchField
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.displayDayMonth
import com.joetr.mira.ui.theme.MiraMotion
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme

/**
 * Local search over the SQLite database.
 *
 * Text matching is a `LIKE` over body and tags; the chips below are structured filters. Audio and
 * video content is not searchable — Mira does no transcription — so media entries are found by
 * date, tag, and any caption typed alongside them.
 */
@Composable
public fun SearchScreen(
    query: String,
    filters: SearchFilters,
    results: List<Entry>,
    onQueryChange: (String) -> Unit,
    onFiltersChange: (SearchFilters) -> Unit,
    onEntryClick: (Entry) -> Unit,
    modifier: Modifier = Modifier,
    availableTags: List<String> = emptyList(),
) {
    val colors = MiraTheme.colors
    val tagOptions = remember(availableTags, filters.tag) {
        buildList {
            filters.tag?.let { add(it) }
            availableTags.forEach { tag ->
                if (none { it.equals(tag, ignoreCase = true) }) add(tag)
            }
        }.take(12)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .imePadding()
            .padding(horizontal = MiraSpacing.gutter),
    ) {
        Spacer(Modifier.height(MiraSpacing.lg))

        SearchField(value = query, onValueChange = onQueryChange)

        Spacer(Modifier.height(MiraSpacing.md))

        Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
            Chip(
                text = "Audio",
                selected = filters.hasAudio,
                onClick = { onFiltersChange(filters.copy(hasAudio = !filters.hasAudio)) },
            )
            Chip(
                text = "Video",
                selected = filters.hasVideo,
                onClick = { onFiltersChange(filters.copy(hasVideo = !filters.hasVideo)) },
            )
        }

        if (tagOptions.isNotEmpty()) {
            Spacer(Modifier.height(MiraSpacing.md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "TAGS",
                    style = MiraTheme.type.sectionLabel,
                    color = colors.inkFaint,
                )
                tagOptions.forEach { tag ->
                    val selected = filters.tag?.equals(tag, ignoreCase = true) == true
                    Chip(
                        text = "#$tag",
                        selected = selected,
                        onClick = {
                            onFiltersChange(filters.copy(tag = if (selected) null else tag))
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(MiraSpacing.xl))

        when {
            query.isBlank() && !filters.isActive -> {
                SectionLabel("Search")
                EmptyState("Find anything you have written.")
            }
            results.isEmpty() -> {
                SectionLabel("No results")
                EmptyState("Nothing matches that.")
            }
            else -> {
                SectionLabel("${results.size} ${if (results.size == 1) "entry" else "entries"}")
                Spacer(Modifier.height(MiraSpacing.md))
                LazyColumn(contentPadding = PaddingValues(bottom = MiraSpacing.giant)) {
                    items(results, key = { it.id }) { entry ->
                        ResultRow(
                            entry = entry,
                            query = query,
                            onClick = { onEntryClick(entry) },
                            modifier = if (MiraTheme.motionEnabled) {
                                Modifier.animateItem(
                                    fadeInSpec = tween(MiraMotion.STANDARD_MS),
                                    placementSpec = tween(MiraMotion.STANDARD_MS),
                                    fadeOutSpec = tween(MiraMotion.QUICK_MS),
                                )
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    entry: Entry,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = MiraSpacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MiraSpacing.lg),
    ) {
        Text(
            text = entry.localDate.displayDayMonth(),
            style = MiraTheme.type.timestamp,
            color = colors.inkMuted,
            modifier = Modifier.width(72.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = highlight(entry.excerpt(), query, colors.accent),
                style = MiraTheme.type.entryExcerpt,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.tags.isNotEmpty()) {
                Spacer(Modifier.height(MiraSpacing.xs))
                EntryTagLine(entry.tags)
            }
        }
        AttachmentDots(entry, modifier = Modifier.padding(top = MiraSpacing.xs))
    }
}

/** Paints the matched term in the accent — the only place a result row is coloured. */
private fun highlight(
    text: String,
    query: String,
    accent: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val index = text.indexOf(query, ignoreCase = true)
    if (index < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, index))
        withStyle(SpanStyle(color = accent)) {
            append(text.substring(index, index + query.length))
        }
        append(text.substring(index + query.length))
    }
}
