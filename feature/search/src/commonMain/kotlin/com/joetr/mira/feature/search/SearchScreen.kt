package com.joetr.mira.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.joetr.mira.ui.components.SearchField
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.displayDayMonth
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
) {
    val colors = MiraTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
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
                        ResultRow(entry, query) { onEntryClick(entry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(entry: Entry, query: String, onClick: () -> Unit) {
    val colors = MiraTheme.colors
    Row(
        modifier = Modifier
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
        Text(
            text = highlight(entry.excerpt(), query, colors.accent),
            style = MiraTheme.type.entryExcerpt,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
