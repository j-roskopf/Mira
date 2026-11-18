package com.joetr.mira.media

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ArchiveMediaFitTest {
    @Test
    fun mediaUnderTheCeilingIsReturnedUnchanged() = runBlocking {
        val file = tempFile(byteArrayOf(1, 2, 3))
        try {
            val result = fitArchiveMedia(request(file, hardLimit = 100, partLimit = 50))
            val whole = assertIs<ArchiveFitResult.Whole>(result)
            assertEquals(file.absolutePath, whole.path)
            assertEquals(false, whole.transcoded)
            result.cleanup()
        } finally {
            file.delete()
        }
    }

    @Test
    fun oversizedMediaIsSplitIntoLosslessParts() = runBlocking {
        val bytes = ByteArray(23) { it.toByte() }
        val file = tempFile(bytes)
        try {
            // A fake mp4 cannot be transcoded, so the lossless split path is exercised.
            val result = fitArchiveMedia(
                request(file, hardLimit = 9, target = 9, partLimit = 5),
            )
            val split = assertIs<ArchiveFitResult.Split>(result)
            assertEquals(5, split.parts.size)
            val restored = ByteArray(23)
            var offset = 0
            for (part in split.parts) {
                val partFile = File(part.path!!)
                val content = partFile.readBytes()
                assertEquals(content.size.toLong(), part.sizeBytes)
                assertTrue(content.size <= 5)
                content.copyInto(restored, offset)
                offset += content.size
            }
            assertContentEquals(bytes, restored)
            val directory = File(split.parts.first().path!!).parentFile!!
            split.cleanup()
            assertFalse(directory.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun aMissingSourceFails() = runBlocking {
        val result = fitArchiveMedia(
            ArchiveFitRequest(
                path = "/definitely/not/here.mp4",
                bytes = null,
                mime = "video/mp4",
                durationMs = 1000,
                targetBytes = 10,
                hardLimitBytes = 10,
                partLimitBytes = 10,
            ),
        )
        assertIs<ArchiveFitResult.Failed>(result)
        Unit
    }

    @Test
    fun transcodesRealVideoUnderTheByteBudget() = runBlocking {
        assumeTrue("FFmpeg is not available", FfmpegBinary.isAvailable)
        val binary = FfmpegBinary.path ?: return@runBlocking
        val directory = File.createTempFile("mira-fit-source-", "").apply { delete(); mkdirs() }
        val source = File(directory, "source.mp4")
        try {
            val generated = ProcessBuilder(
                listOf(
                    binary, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "testsrc=size=640x360:rate=30",
                    "-f", "lavfi", "-i", "sine=frequency=440",
                    "-t", "2",
                ) + videoEncoderArgs(2_000_000) + listOf(
                    "-c:a", "aac", "-b:a", "96k", source.absolutePath,
                ),
            ).redirectErrorStream(true).start()
            val output = generated.inputStream.bufferedReader().use { it.readText() }
            if (!generated.waitFor(60, TimeUnit.SECONDS) || generated.exitValue() != 0) {
                assumeTrue("Could not generate a source clip: $output", false)
                return@runBlocking
            }
            val result = fitArchiveMedia(
                ArchiveFitRequest(
                    path = source.absolutePath,
                    bytes = null,
                    mime = "video/mp4",
                    durationMs = 2000,
                    targetBytes = 50_000,
                    hardLimitBytes = 80_000,
                    partLimitBytes = 80_000,
                ),
            )
            when (result) {
                is ArchiveFitResult.Whole -> {
                    assertTrue(result.transcoded)
                    assertTrue(result.sizeBytes <= 80_000)
                }
                is ArchiveFitResult.Split -> assertTrue(result.parts.all { it.sizeBytes <= 80_000 })
                else -> fail("Expected the clip to be re-encoded or split, got $result")
            }
            result.cleanup()
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun request(
        file: File,
        hardLimit: Long,
        partLimit: Long,
        target: Long = hardLimit,
    ) = ArchiveFitRequest(
        path = file.absolutePath,
        bytes = null,
        mime = "video/mp4",
        durationMs = 0,
        targetBytes = target,
        hardLimitBytes = hardLimit,
        partLimitBytes = partLimit,
    )

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("mira-fit-", ".mp4").apply { writeBytes(bytes) }
}
