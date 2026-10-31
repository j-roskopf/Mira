package com.joetr.mira.feature.timeline

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.Entry
import com.joetr.mira.ui.components.AttachmentDots
import com.joetr.mira.ui.components.EmptyState
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.displayDayMonth
import com.joetr.mira.ui.displayName
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme

/**
 * Every entry, newest first, grouped by month.
 *
 * Rows are date, a two-line excerpt and attachment dots — no cards, no dividers. The scroll
 * position is kept by the caller so coming back does not dump you at today.
 */
@Composable
public fun TimelineScreen(
    entries: List<Entry>,
    onEntryClick: (Entry) -> Unit,
    modifier: Modifier = Modifier,
    onLoadMore: () -> Unit = {},
) {
    val colors = MiraTheme.colors
    val listState = rememberLazyListState()
    val grouped = remember(entries) { entries.groupBy { it.localDate.monthKey } }

    if (entries.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(colors.canvas)
                .padding(horizontal = MiraSpacing.gutter),
        ) {
            Spacer(Modifier.height(MiraSpacing.giant))
            EmptyState("Nothing written yet.")
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.canvas),
        state = listState,
        contentPadding = PaddingValues(
            start = MiraSpacing.gutter,
            end = MiraSpacing.gutter,
            top = MiraSpacing.lg,
            bottom = MiraSpacing.giant,
        ),
    ) {
        grouped.forEach { (month, monthEntries) ->
            item(key = "header-${month.value}") {
                Column {
                    Spacer(Modifier.height(MiraSpacing.xl))
                    SectionLabel("${month.displayName()} ${month.year}")
                    Spacer(Modifier.height(MiraSpacing.md))
                }
            }
            items(monthEntries, key = { it.id }) { entry ->
                TimelineRow(entry = entry, onClick = { onEntryClick(entry) })
            }
        }
        item {
            Spacer(Modifier.height(MiraSpacing.xxl))
            onLoadMore()
        }
    }
}

@Composable
private fun TimelineRow(entry: Entry, onClick: () -> Unit) {
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
            text = entry.excerpt(),
            style = MiraTheme.type.entryExcerpt,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AttachmentDots(entry, modifier = Modifier.padding(top = MiraSpacing.xs))
    }
}
