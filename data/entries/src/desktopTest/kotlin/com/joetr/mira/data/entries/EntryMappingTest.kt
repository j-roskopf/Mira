package com.joetr.mira.data.entries

import com.joetr.mira.db.Entries
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.AttachmentState
import com.joetr.mira.network.RemoteAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntryMappingTest {
    @Test
    fun pendingAttachmentIsPublishedSoOtherDevicesCanShowIt() {
        val pending = Attachment(
            id = "video",
            kind = AttachmentKind.VIDEO,
            storagePath = "",
            mime = "video/mp4",
            state = AttachmentState.LOCAL,
        )
        val row = entriesRow(listOf(pending)).toRemoteRow()

        val remote = row.attachments.single()
        assertEquals("video", remote.id)
        assertEquals("", remote.storagePath)
        assertEquals("uploading", remote.state)
    }

    @Test
    fun uploadedAttachmentIsPublishedAsComplete() {
        val uploaded = Attachment(
            id = "video",
            kind = AttachmentKind.VIDEO,
            storagePath = "users/owner/media/entry/video.mp4",
            url = "https://example.test/video.mp4",
            mime = "video/mp4",
            state = AttachmentState.UPLOADED,
        )
        val remote = entriesRow(listOf(uploaded)).toRemoteRow().attachments.single()

        assertEquals("uploaded", remote.state)
        assertEquals("users/owner/media/entry/video.mp4", remote.storagePath)
    }

    @Test
    fun remotePendingAttachmentIsNotMistakenForALocalCopy() {
        val attachment = remoteAttachment(state = "uploading", storagePath = "").toAttachment()

        assertEquals(AttachmentState.UPLOADING, attachment.state)
        assertTrue(!attachment.isUploaded)
    }

    @Test
    fun legacyRemoteAttachmentWithoutStateIsUploaded() {
        val attachment = remoteAttachment(state = null, storagePath = "users/owner/media/e/a.mp4")
            .toAttachment()

        assertEquals(AttachmentState.UPLOADED, attachment.state)
        assertTrue(attachment.isUploaded)
    }

    private fun entriesRow(attachments: List<Attachment>) = Entries(
        id = "entry",
        owner_id = "owner",
        local_date = "2026-11-12",
        created_at = 1L,
        created_tz = "UTC",
        updated_at = 1L,
        body = "Recorded something",
        attachments = encodeAttachments(attachments),
        tags = encodeTags(emptyList()),
        deleted = 0L,
        pending_sync = 1L,
    )

    private fun remoteAttachment(state: String?, storagePath: String) = RemoteAttachment(
        id = "video",
        kind = "video",
        storagePath = storagePath,
        url = null,
        mime = "video/mp4",
        bytes = 1L,
        durationMs = 0L,
        posterPath = null,
        posterUrl = null,
        thumbnailPath = null,
        thumbnailUrl = null,
        caption = "",
        state = state,
    )
}
