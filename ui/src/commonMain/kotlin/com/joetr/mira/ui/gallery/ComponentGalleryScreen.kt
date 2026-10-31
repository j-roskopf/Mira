package com.joetr.mira.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.AttachmentState
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.ui.components.AttachmentDots
import com.joetr.mira.ui.components.Chip
import com.joetr.mira.ui.components.CircleIconButton
import com.joetr.mira.ui.components.DayHeader
import com.joetr.mira.ui.components.EmptyState
import com.joetr.mira.ui.components.EntryCard
import com.joetr.mira.ui.components.HeatmapDay
import com.joetr.mira.ui.components.MiraCard
import com.joetr.mira.ui.components.PillButton
import com.joetr.mira.ui.components.RecordButton
import com.joetr.mira.ui.components.SearchField
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.components.SyncIndicator
import com.joetr.mira.ui.icons.MiraIcon
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.theme.MiraAccent
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme

/**
 * A live catalogue of every component, every accent and both themes.
 *
 * It exists to be opened on each platform: it is how a web build rendering the type stack wrong, or
 * a light-mode token that was never checked, gets caught before a feature screen ships it.
 */
@Composable
public fun ComponentGalleryScreen(
    modifier: Modifier = Modifier,
    darkTheme: Boolean? = null,
    accent: MiraAccent = MiraAccent.Default,
) {
    // Screenshot tests drive the theme directly; in the app it comes from the ambient MiraTheme.
    if (darkTheme != null) {
        MiraTheme(darkTheme = darkTheme, accent = accent, motionEnabled = false) {
            GalleryContent(modifier)
        }
    } else {
        GalleryContent(modifier)
    }
}

@Composable
private fun GalleryContent(modifier: Modifier = Modifier) {
    val colors = MiraTheme.colors
    var searchQuery by remember { mutableStateOf("") }
    var selectedChip by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(MiraSpacing.gutter),
    ) {
        Text("MIRA COMPONENTS", style = MiraTheme.type.monthTitle, color = colors.ink)
        Spacer(Modifier.height(MiraSpacing.sectionGap))

        GallerySection("Type scale") {
            Text("OCTOBER", style = MiraTheme.type.monthTitle, color = colors.ink)
            Text("TUESDAY", style = MiraTheme.type.dayTitle, color = colors.ink)
            Text("30 OCT 2026", style = MiraTheme.type.dayDate, color = colors.inkMuted)
            SectionLabel("Section label")
            Text(
                "Entry body set in Literata. The rule is that if the user typed it, it is " +
                    "Literata; if the app says it, it is Inter Tight.",
                style = MiraTheme.type.entryBody,
                color = colors.ink,
            )
            Text("11:47 PM", style = MiraTheme.type.timestamp, color = colors.inkMuted)
        }

        GallerySection("Heatmap ramp") {
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                (-1..4).forEach { step ->
                    HeatmapDay(
                        day = step + 2,
                        rampStep = step,
                        isToday = step == -1,
                        isSelected = false,
                    )
                }
            }
            Text(
                "Step -1 draws no circle at all — that absence is the point.",
                style = MiraTheme.type.meta,
                color = colors.inkMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                HeatmapDay(day = 30, rampStep = 2, isToday = true, isSelected = false)
                HeatmapDay(day = 31, rampStep = 4, isToday = false, isSelected = true)
            }
        }

        GallerySection("Accents") {
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                MiraAccent.entries.take(5).forEach { accent ->
                    AccentSwatch(accent)
                }
            }
            Spacer(Modifier.height(MiraSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                MiraAccent.entries.drop(5).forEach { accent ->
                    AccentSwatch(accent)
                }
            }
        }

        GallerySection("Day header") {
            DayHeader(LocalDateKey("2026-10-30"))
        }

        GallerySection("Entries") {
            EntryCard(sampleTextEntry)
            Spacer(Modifier.height(MiraSpacing.cardGap))
            EntryCard(sampleMediaEntry)
            Spacer(Modifier.height(MiraSpacing.cardGap))
            MiraCard { Text("Plain card", style = MiraTheme.type.entryBody, color = colors.ink) }
        }

        GallerySection("Empty state") {
            EmptyState("Nothing yet.")
        }

        GallerySection("Controls") {
            SearchField(value = searchQuery, onValueChange = { searchQuery = it })
            Spacer(Modifier.height(MiraSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                listOf("All", "Audio", "Video").forEachIndexed { index, label ->
                    Chip(label, selected = selectedChip == index, onClick = { selectedChip = index })
                }
            }
            Spacer(Modifier.height(MiraSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                PillButton("Save", onClick = {})
                PillButton("Cancel", onClick = {}, filled = false)
                PillButton("Disabled", onClick = {}, enabled = false)
            }
            Spacer(Modifier.height(MiraSpacing.md))
            Row(
                horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(MiraIcons.Plus, "Add", onClick = {}, filled = true)
                CircleIconButton(MiraIcons.Back, "Back", onClick = {})
                CircleIconButton(MiraIcons.Close, "Close", onClick = {})
                RecordButton(recording = recording, onClick = { recording = !recording })
            }
        }

        GallerySection("Status") {
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.lg)) {
                SyncIndicator(pendingCount = 0)
                SyncIndicator(pendingCount = 3)
                AttachmentDots(sampleMediaEntry)
            }
        }

        GallerySection("Icons") {
            val icons = listOf(
                MiraIcons.Calendar, MiraIcons.Timeline, MiraIcons.Search, MiraIcons.Settings,
                MiraIcons.Microphone, MiraIcons.Video, MiraIcons.Note, MiraIcons.Play,
                MiraIcons.Pause, MiraIcons.Trash, MiraIcons.Attachment, MiraIcons.CloudDone,
                MiraIcons.CloudPending, MiraIcons.GitHub, MiraIcons.Download, MiraIcons.Tag,
            )
            Column(verticalArrangement = Arrangement.spacedBy(MiraSpacing.md)) {
                icons.chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.lg)) {
                        row.forEach { icon ->
                            MiraIcon(icon, tint = colors.ink, size = 22.dp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(MiraSpacing.giant))
    }
}

@Composable
private fun GallerySection(title: String, content: @Composable () -> Unit) {
    Column {
        SectionLabel(title)
        Spacer(Modifier.height(MiraSpacing.md))
        content()
        Spacer(Modifier.height(MiraSpacing.sectionGap))
    }
}

@Composable
private fun AccentSwatch(accent: MiraAccent) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(32.dp).background(accent.color, CircleShape))
        Spacer(Modifier.height(MiraSpacing.xs))
        Text(
            accent.displayName,
            style = MiraTheme.type.meta,
            color = MiraTheme.colors.inkMuted,
        )
    }
}

private val sampleTextEntry = Entry(
    id = "sample-text",
    ownerId = "local",
    localDate = LocalDateKey("2026-10-30"),
    createdAt = 1_793_000_000_000,
    createdTz = "America/Chicago",
    updatedAt = 1_793_000_000_000,
    body = "Walked the long way home because the light was doing that thing it does in late " +
        "October, where everything looks like it is remembering itself.",
)

private val sampleMediaEntry = Entry(
    id = "sample-media",
    ownerId = "local",
    localDate = LocalDateKey("2026-10-30"),
    createdAt = 1_793_010_000_000,
    createdTz = "America/Chicago",
    updatedAt = 1_793_010_000_000,
    body = "Voice note from the car.",
    attachments = listOf(
        Attachment(
            id = "a1",
            kind = AttachmentKind.AUDIO,
            storagePath = "users/local/media/sample/voice.m4a",
            mime = "audio/mp4",
            durationMs = 94_000,
            state = AttachmentState.UPLOADED,
        ),
        Attachment(
            id = "a2",
            kind = AttachmentKind.VIDEO,
            storagePath = "users/local/media/sample/clip.mp4",
            mime = "video/mp4",
            durationMs = 12_000,
            state = AttachmentState.UPLOADING,
        ),
    ),
)
