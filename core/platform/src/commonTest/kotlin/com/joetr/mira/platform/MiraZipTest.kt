package com.joetr.mira.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MiraZipTest {

    @Test
    fun roundTripsEmptyArchive() {
        val bytes = MiraZip.write(emptyList())
        assertEquals(emptyMap(), MiraZip.read(bytes))
    }

    @Test
    fun roundTripsTextAndBinaryEntries() {
        val binary = ByteArray(5000) { (it % 251).toByte() }
        val entries = listOf(
            "manifest.json" to """{"schemaVersion":1}""".encodeToByteArray(),
            "entries/2026/10/2026-10-30--143022--a3f9.md" to "---\nid: a3f9\n---\n\nHello.".encodeToByteArray(),
            "media/2026/10/a3f9/voice.m4a" to binary,
            "empty.txt" to ByteArray(0),
        )

        val zipped = MiraZip.write(entries)
        val read = MiraZip.read(zipped)

        assertEquals(entries.size, read.size)
        entries.forEach { (name, data) ->
            assertTrue(read.containsKey(name), "missing entry $name")
            assertTrue(data.contentEquals(read.getValue(name)), "mismatched bytes for $name")
        }
    }

    @Test
    fun rejectsNonZipData() {
        assertFailsWith<IllegalArgumentException> {
            MiraZip.read("not a zip file".encodeToByteArray())
        }
    }
}
