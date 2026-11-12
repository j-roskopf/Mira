package com.joetr.mira.data.entries

import com.joetr.mira.data.entries.sync.mergeAttachments
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.AttachmentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentMergeTest {
    @Test
    fun remotePendingPlaceholderKeepsThisDevicesStagedCopy() {
        val local = attachment("video", AttachmentState.LOCAL, storagePath = "")
        val remote = attachment("video", AttachmentState.UPLOADING, storagePath = "")

        val merged = mergeAttachments(listOf(remote), listOf(local))

        assertEquals(AttachmentState.LOCAL, merged.single().state)
    }

    @Test
    fun uploadedRemoteCopyWinsOverALocalPlaceholder() {
        val local = attachment("video", AttachmentState.LOCAL, storagePath = "")
        val remote = attachment("video", AttachmentState.UPLOADED, storagePath = "users/o/media/e/video.mp4")

        val merged = mergeAttachments(listOf(remote), listOf(local))

        assertEquals(AttachmentState.UPLOADED, merged.single().state)
        assertEquals("users/o/media/e/video.mp4", merged.single().storagePath)
    }

    @Test
    fun localUploadedCopyWinsOverAStaleRemotePlaceholder() {
        val local = attachment("video", AttachmentState.UPLOADED, storagePath = "users/o/media/e/video.mp4")
        val remote = attachment("video", AttachmentState.UPLOADING, storagePath = "")

        val merged = mergeAttachments(listOf(remote), listOf(local))

        assertEquals(AttachmentState.UPLOADED, merged.single().state)
    }

    @Test
    fun uploadedAttachmentMissingFromRemoteWasDeletedThere() {
        val local = attachment("video", AttachmentState.UPLOADED, storagePath = "users/o/media/e/video.mp4")

        val merged = mergeAttachments(emptyList(), listOf(local))

        assertTrue(merged.isEmpty())
    }

    private fun attachment(
        id: String,
        state: AttachmentState,
        storagePath: String,
    ) = Attachment(
        id = id,
        kind = AttachmentKind.VIDEO,
        storagePath = storagePath,
        mime = "video/mp4",
        state = state,
    )
}
