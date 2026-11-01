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
}
