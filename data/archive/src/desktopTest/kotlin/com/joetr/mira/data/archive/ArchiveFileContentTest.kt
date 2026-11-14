package com.joetr.mira.data.archive

import io.ktor.client.engine.mock.toByteArray
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ArchiveFileContentTest {
    @Test
    fun streamsBase64AcrossChunkBoundaries() = runTest {
        val sizes = listOf(0, 1, 2, 3, 4, 60 * 1024 - 1, 60 * 1024, 60 * 1024 + 1, 200_000)
        for (size in sizes) {
            val source = File.createTempFile("mira-archive-content-", ".bin")
            try {
                val bytes = ByteArray(size) { (it % 251).toByte() }
                source.writeBytes(bytes)
                val content = assertNotNull(base64JsonFileContent(source.absolutePath))
                val body = content.toByteArray()
                assertEquals(body.size.toLong(), content.contentLength)
                val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
                assertEquals("base64", json.getValue("encoding").jsonPrimitive.content)
                val decoded = Base64.getDecoder().decode(json.getValue("content").jsonPrimitive.content)
                assertContentEquals(bytes, decoded)
            } finally {
                source.delete()
            }
        }
    }
}
