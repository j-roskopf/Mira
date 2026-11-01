package com.joetr.mira.data.entries.media

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RecordingStorageTest {
    @Test
    fun stagedRecordingSurvivesRecorderReleaseAndCacheDeletion() {
        val root = Files.createTempDirectory("mira-recording-test").toFile()
        try {
            val cache = File(root, "cache").apply { mkdirs() }
            val recording = File(cache, "capture.mp4").apply {
                writeBytes(byteArrayOf(0, 1, 2, 3, 4))
            }
            val expected = recording.readBytes()
            val stored = copyRecordingToStorage(recording, File(root, "files/media"))
            assertNotEquals(recording.absolutePath, stored)
            assertTrue(recording.exists())
            cache.deleteRecursively()
            assertContentEquals(expected, File(stored).readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingOrEmptyRecordingCannotBeStaged() {
        val root = Files.createTempDirectory("mira-recording-test").toFile()
        try {
            val recording = File(root, "capture.mp4")
            val storage = File(root, "media")
            assertFailsWith<IllegalStateException> { copyRecordingToStorage(recording, storage) }
            recording.createNewFile()
            assertFailsWith<IllegalStateException> { copyRecordingToStorage(recording, storage) }
        } finally {
            root.deleteRecursively()
        }
    }
}
