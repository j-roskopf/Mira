package com.joetr.mira.data.archive

import com.joetr.mira.domain.*
import kotlin.test.*

class EntryMarkdownTest {
    @Test
    fun linksOnlyBackedUpRecordingsUsingPathsRelativeToNote() {
        val video = Attachment("video", AttachmentKind.VIDEO, "", mime = "video/mp4")
        val audio = Attachment("audio", AttachmentKind.AUDIO, "", mime = "audio/mp4")
        val missing = Attachment("missing", AttachmentKind.VIDEO, "", mime = "video/mp4")
        val entry = Entry(
            id = "entry", ownerId = "owner", localDate = LocalDateKey("2026-10-31"),
            createdAt = 1L, createdTz = "UTC", updatedAt = 1L, body = "qwerty",
            attachments = listOf(video, audio, missing),
        )
        val markdown = entry.toMarkdown(setOf(video.archiveMediaPath(entry), audio.archiveMediaPath(entry)))
        assertTrue(markdown.contains("qwerty"))
        assertTrue(markdown.contains("[Watch video 1](../../../media/2026/10/entry/video.mp4)"))
        assertTrue(markdown.contains("[Listen to audio 2](../../../media/2026/10/entry/audio.m4a)"))
        assertFalse(markdown.contains("missing.mp4"))
        assertFalse(entry.toMarkdown().contains("## Recordings"))
    }

    @Test
    fun splitRecordingListsOrderedPartsAndReassembly() {
        val video = Attachment("video", AttachmentKind.VIDEO, "", mime = "video/mp4")
        val entry = Entry(
            id = "entry", ownerId = "owner", localDate = LocalDateKey("2026-10-31"),
            createdAt = 1L, createdTz = "UTC", updatedAt = 1L,
            attachments = listOf(video),
        )
        val parts = listOf(
            video.archivePartPath(entry, 1, 2),
            video.archivePartPath(entry, 2, 2),
        )
        val markdown = entry.toMarkdown(mapOf(video.id to ArchivedMedia(parts)))
        assertTrue(markdown.contains("split into 2 parts"))
        assertTrue(markdown.contains("[part 1](../../../${parts[0]})"))
        assertTrue(markdown.contains("[part 2](../../../${parts[1]})"))
        assertTrue(markdown.contains("cat part*.mp4"))
    }

}
