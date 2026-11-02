package com.joetr.mira.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraFormatTest {
    @Test
    fun defaultCapturePreservesCameraOrientation() {
        assertEquals(VideoAspectRatio.ORIGINAL, VideoCaptureSettings().aspectRatio)
        assertNull(preferredCameraSize(VideoCaptureSettings()))
    }

    @Test
    fun selectedShapeRequestsCameraOrientationInsteadOfOnlyChangingOutputCanvas() {
        assertEquals(CameraSize(1920, 1080), preferredCameraSize(VideoCaptureSettings(VideoAspectRatio.LANDSCAPE)))
        assertEquals(CameraSize(1280, 720), preferredCameraSize(VideoCaptureSettings(VideoAspectRatio.LANDSCAPE, quality = VideoQuality.STANDARD)))
        assertEquals(CameraSize(1080, 1920), preferredCameraSize(VideoCaptureSettings(VideoAspectRatio.PORTRAIT)))
        assertEquals(CameraSize(1920, 1080), preferredCameraSize(VideoCaptureSettings(VideoAspectRatio.SQUARE)))
        assertNull(preferredCameraSize(VideoCaptureSettings(VideoAspectRatio.ORIGINAL)))
    }

    @Test
    fun unsupportedResolutionFallsBackToClosestSupportedModeWithSameOrientation() {
        val modes = """
            Supported modes:
              640x480@[15.000000 30.000000]fps
              1280x720@[15.000000 30.000000]fps
              1920x1080@[60.000000 60.000000]fps
              1080x1920@[15.000000 30.000000]fps
        """.trimIndent()
        assertEquals(CameraSize(1280, 720), fallbackCameraSize(modes, CameraSize(1920, 1080)))
        assertEquals(CameraSize(1080, 1920), fallbackCameraSize(modes, CameraSize(720, 1280)))
        assertNull(fallbackCameraSize("Permission denied", CameraSize(1920, 1080)))
    }

    @Test
    fun macInputCarriesTheRequestedSize() {
        if (desktopOs != DesktopOs.MAC) return
        val arguments = videoInputArgs(videoSize = CameraSize(1920, 1080))
        assertEquals("1920x1080", arguments[arguments.indexOf("-video_size") + 1])
    }

    @Test
    fun framingPreviewNeverOpensTheMicrophone() {
        if (desktopOs != DesktopOs.MAC) return
        val arguments = videoOnlyInputArgs()
        assertEquals("0", arguments[arguments.indexOf("-i") + 1])
    }
}
