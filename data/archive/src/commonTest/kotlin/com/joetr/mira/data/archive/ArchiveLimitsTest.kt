package com.joetr.mira.data.archive

import com.joetr.mira.domain.GITHUB_ARCHIVE_MAX_MEDIA_BYTES
import com.joetr.mira.domain.GITHUB_ARCHIVE_PART_MAX_BYTES
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchiveLimitsTest {
    /**
     * The Git Data API rejects a blob request whose JSON body is above roughly 40 MiB. Base64 adds
     * a third, so the decoded blob must stay well below 30 MiB for the request to be accepted.
     */
    @Test
    fun everyBlobStaysUnderTheApiPayloadLimit() {
        val apiPayloadLimit = 40L * 1024 * 1024
        for (bytes in listOf(GITHUB_ARCHIVE_MAX_MEDIA_BYTES, GITHUB_ARCHIVE_PART_MAX_BYTES)) {
            val base64 = ((bytes + 2) / 3) * 4
            assertTrue(base64 < apiPayloadLimit, "$bytes bytes encodes to $base64")
        }
    }
}
