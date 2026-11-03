package com.joetr.mira.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.feature.compose.ComposeEntryScreen
import com.joetr.mira.media.createMediaRecorderController
import com.joetr.mira.ui.theme.MiraTheme
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class EntryEditingDesktopTest {
    @Test
    fun loadedNoteAppearsAndEditsReachSave() = runDesktopComposeUiTest {
        val body = mutableStateOf("")
        var saved: String? = null
        setContent {
            MiraTheme(darkTheme = true, motionEnabled = false) {
                val recorder = remember { createMediaRecorderController() }
                ComposeEntryScreen(
                    date = LocalDateKey("2026-10-31"),
                    initialBody = body.value,
                    recorder = recorder,
                    attachments = emptyList(),
                    sourceFor = { null },
                    onRemoveAttachment = {},
                    onBodyChange = { body.value = it },
                    onDateClick = {},
                    onCaptureFinished = {}, onMediaPicked = {},
                    onSave = { saved = body.value },
                    onCancel = {},
                    onDelete = {},
                )
            }
        }
        runOnIdle { body.value = "Previously saved note" }
        onNode(hasSetTextAction()).assertTextEquals("Previously saved note")
        onNode(hasSetTextAction()).performTextReplacement("Updated note")
        onNodeWithContentDescription("Save changes").performClick()
        runOnIdle { assertEquals("Updated note", saved) }
    }

    @Test
    fun tagCanBeAddedFromEditorAndRemoved() = runDesktopComposeUiTest {
        val tags = mutableStateOf(listOf("work"))
        var added: String? = null
        var removed: String? = null
        setContent {
            MiraTheme(darkTheme = true, motionEnabled = false) {
                val recorder = remember { createMediaRecorderController() }
                ComposeEntryScreen(
                    date = LocalDateKey("2026-10-31"),
                    initialBody = "Note",
                    recorder = recorder,
                    attachments = emptyList(),
                    tags = tags.value,
                    recentTags = listOf("walk", "travel"),
                    onAddTag = { tag ->
                        added = tag
                        tags.value = tags.value + tag
                    },
                    onRemoveTag = { tag ->
                        removed = tag
                        tags.value = tags.value - tag
                    },
                    sourceFor = { null },
                    onRemoveAttachment = {},
                    onBodyChange = {},
                    onDateClick = {},
                    onCaptureFinished = {}, onMediaPicked = {},
                    onSave = {},
                    onCancel = {},
                )
            }
        }

        onNodeWithContentDescription("Add tag").performClick()
        onNodeWithContentDescription("Tag name").performTextInput("travel")
        onNodeWithContentDescription("Add tag").performClick()
        runOnIdle {
            assertEquals("travel", added)
            assertEquals(listOf("work", "travel"), tags.value)
        }

        onNodeWithContentDescription("Remove tag #travel").performClick()
        runOnIdle {
            assertEquals("travel", removed)
            assertEquals(listOf("work"), tags.value)
        }
    }
}
