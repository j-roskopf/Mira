package com.joetr.mira.feature.day

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.ui.components.CircleIconButton
import com.joetr.mira.ui.components.DayHeader
import com.joetr.mira.ui.components.EmptyState
import com.joetr.mira.ui.components.EntryCard
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.motion.sharedDayTitle
import com.joetr.mira.ui.motion.sharedEntryCard
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
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    modifier: Modifier = Modifier,
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
                CircleIconButton(MiraIcons.Back, "Back", onClick = onBack)
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

            if (entries.isEmpty()) {
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
                            modifier = Modifier.sharedEntryCard(entry.id),
                        )
                    }
                }
            }
        }
    }
}
