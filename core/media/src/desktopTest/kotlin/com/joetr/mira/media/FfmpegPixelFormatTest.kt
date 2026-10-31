package com.joetr.mira.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry path depends on reading FFmpeg's own device report, so the parsing is pinned to real
 * output rather than a hand-written approximation.
 */
class FfmpegPixelFormatTest {

    /** Verbatim from `ffmpeg -f avfoundation -framerate 30 -i 0:0` on a MacBook Pro camera. */
    private val realFailure = """
        [in#0 @ 0x7797014000] Selected pixel format (yuv420p) is not supported by the input device.
        [in#0 @ 0x7797014000] Supported pixel formats:
        [in#0 @ 0x7797014000]   uyvy422
        [in#0 @ 0x7797014000]   yuyv422
        [in#0 @ 0x7797014000]   nv12
        [in#0 @ 0x7797014000]   0rgb
        [in#0 @ 0x7797014000]   bgr0
    """.trimIndent()

    @Test
    fun readsTheDeviceReportedFormats() {
        assertEquals(
            listOf("uyvy422", "yuyv422", "nv12", "0rgb", "bgr0"),
            parseSupportedPixelFormats(realFailure),
        )
    }

    @Test
    fun picksTheBestFormatTheDeviceActuallyOffers() {
        val offered = parseSupportedPixelFormats(realFailure)
        assertEquals("nv12", MAC_PIXEL_FORMATS.first { it in offered })
    }

    @Test
    fun ignoresUnrelatedStderr() {
        assertTrue(parseSupportedPixelFormats("").isEmpty())
        assertTrue(
            parseSupportedPixelFormats("[in#0] Error opening input: Input/output error").isEmpty(),
        )
    }

    /** A device offering none of our preferences still has to yield something to retry with. */
    @Test
    fun fallsBackToWhateverTheDeviceListsFirst() {
        val exotic = """
            [in#0] Selected pixel format (yuv420p) is not supported by the input device.
            [in#0] Supported pixel formats:
            [in#0]   rgb555be
        """.trimIndent()
        val offered = parseSupportedPixelFormats(exotic)
        assertEquals(listOf("rgb555be"), offered)
        assertEquals(null, MAC_PIXEL_FORMATS.firstOrNull { it in offered })
        assertEquals("rgb555be", offered.firstOrNull())
    }

    /** The default must be a format real Mac cameras report; yuv420p is exactly what they do not. */
    @Test
    fun defaultIsNotTheFormatAvfoundationWronglyAssumes() {
        assertTrue(MAC_PIXEL_FORMATS.isNotEmpty())
        assertTrue("yuv420p" !in MAC_PIXEL_FORMATS)
    }
}
