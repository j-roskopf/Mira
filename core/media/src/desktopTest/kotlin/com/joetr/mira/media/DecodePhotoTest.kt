package com.joetr.mira.media

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodePhotoTest {
    @Test
    fun downsamplesToMaxLongEdge() {
        val bytes = solidJpeg(width = 4000, height = 3000)
        val bitmap = decodePhoto(bytes, maxLongEdge = 1000)
        assertNotNull(bitmap)
        assertEquals(1000, maxOf(bitmap!!.width, bitmap.height))
        assertTrue(bitmap.width == 1000 || bitmap.height == 750)
    }

    @Test
    fun leavesSmallPhotosAlone() {
        val bytes = solidJpeg(width = 640, height = 480)
        val bitmap = decodePhoto(bytes, maxLongEdge = 1000)
        assertNotNull(bitmap)
        assertEquals(640, bitmap!!.width)
        assertEquals(480, bitmap.height)
    }

    private fun solidJpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.BLUE
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }
}
