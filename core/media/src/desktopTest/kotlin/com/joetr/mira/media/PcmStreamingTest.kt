package com.joetr.mira.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmStreamingTest {
    @Test
    fun fragmentedReadsAndPartialWritesPreserveEverySample() {
        val samples = ByteArray(40_000) { (it * 17).toByte() }
        val input = object : ByteArrayInputStream(samples) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 7))
        }
        val output = ByteArrayOutputStream()
        copyPcmFrames(input, 4, { true }) { bytes, offset, count ->
            assertEquals(0, count % 4)
            assertEquals(0, offset % 4)
            val written = minOf(count, 12)
            output.write(bytes, offset, written)
            written
        }
        assertArrayEquals(samples, output.toByteArray())
    }

    @Test
    fun cancellationDoesNotWriteRemainingBufferedAudio() {
        var active = true
        var calls = 0
        copyPcmFrames(ByteArrayInputStream(ByteArray(40_000)), 4, { active }) { _, _, _ ->
            calls++
            active = false
            4
        }
        assertEquals(1, calls)
    }
}
