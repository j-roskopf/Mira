package com.joetr.mira.data.archive

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writeFully

/**
 * Reads a preserved recording without copying it into a `ByteArray`.
 *
 * Archiving a long video used to call `File.readBytes()`, which allocated the whole file at once
 * and killed the app on Android. The GitHub blob API still wants base64, but it can be produced in
 * fixed-size chunks as the request body is written.
 */
internal interface ArchiveFileReader {
    val size: Long
    fun read(buffer: ByteArray): Int
    fun close()
}

internal expect fun openArchiveFileReader(path: String): ArchiveFileReader?

/**
 * A JSON blob request whose `content` field is base64-encoded from disk on the fly.
 *
 * Returns null when this platform cannot stream files; callers fall back to an in-memory payload.
 */
internal fun base64JsonFileContent(path: String): OutgoingContent? {
    val reader = openArchiveFileReader(path) ?: return null
    return Base64JsonFileContent(reader)
}

/**
 * Base64 JSON for bytes already in memory.
 *
 * Encoding the whole array into one string first created a second copy of roughly 4/3 the
 * recording, and Ktor then encoded that string to another UTF-8 array. A 56 MB video therefore
 * needed a ~75 MB allocation on top of its own bytes and ran out of heap on mobile. Encoding in
 * chunks as the request is written keeps the extra allocation to a few kilobytes.
 */
internal fun base64JsonBytesContent(bytes: ByteArray): OutgoingContent =
    Base64JsonFileContent(ByteArrayArchiveFileReader(bytes))

private const val JSON_PREFIX = "{\"content\":\""
private const val JSON_SUFFIX = "\",\"encoding\":\"base64\"}"

/** Divisible by three so every full chunk encodes without padding. */
private const val CHUNK_SIZE = 60 * 1024

private val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    .encodeToByteArray()

private class ByteArrayArchiveFileReader(
    private val bytes: ByteArray,
) : ArchiveFileReader {
    private var index = 0

    override val size: Long = bytes.size.toLong()

    override fun read(buffer: ByteArray): Int {
        if (index >= bytes.size) return -1
        val count = minOf(buffer.size, bytes.size - index)
        bytes.copyInto(buffer, 0, index, index + count)
        index += count
        return count
    }

    override fun close() {}
}

private class Base64JsonFileContent(
    private val reader: ArchiveFileReader,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.Application.Json

    override val contentLength: Long =
        JSON_PREFIX.encodeToByteArray().size.toLong() +
            4L * ((reader.size + 2) / 3) +
            JSON_SUFFIX.encodeToByteArray().size

    override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
        channel.writeStringUtf8(JSON_PREFIX)
        val input = ByteArray(CHUNK_SIZE)
        val combined = ByteArray(CHUNK_SIZE + 2)
        val encoded = ByteArray(((CHUNK_SIZE + 2) / 3 + 1) * 4)
        val carry = ByteArray(2)
        var carryCount = 0
        try {
            while (true) {
                val read = reader.read(input)
                if (read < 0) break
                if (read == 0) continue
                carry.copyInto(combined, destinationOffset = 0, startIndex = 0, endIndex = carryCount)
                input.copyInto(combined, destinationOffset = carryCount, startIndex = 0, endIndex = read)
                val total = carryCount + read
                val whole = total - total % 3
                val encodedLength = encodeBase64(combined, whole, pad = false, encoded)
                channel.writeFully(encoded, startIndex = 0, endIndex = encodedLength)
                carryCount = total - whole
                if (carryCount > 0) {
                    combined.copyInto(carry, destinationOffset = 0, startIndex = whole, endIndex = total)
                }
            }
            if (carryCount > 0) {
                val encodedLength = encodeBase64(carry, carryCount, pad = true, encoded)
                channel.writeFully(encoded, startIndex = 0, endIndex = encodedLength)
            }
            channel.writeStringUtf8(JSON_SUFFIX)
        } finally {
            reader.close()
        }
    }
}

private fun encodeBase64(
    source: ByteArray,
    length: Int,
    pad: Boolean,
    destination: ByteArray,
): Int {
    var index = 0
    var output = 0
    while (index + 2 < length) {
        val chunk = ((source[index].toInt() and 0xFF) shl 16) or
            ((source[index + 1].toInt() and 0xFF) shl 8) or
            (source[index + 2].toInt() and 0xFF)
        destination[output++] = BASE64_ALPHABET[(chunk shr 18) and 0x3F]
        destination[output++] = BASE64_ALPHABET[(chunk shr 12) and 0x3F]
        destination[output++] = BASE64_ALPHABET[(chunk shr 6) and 0x3F]
        destination[output++] = BASE64_ALPHABET[chunk and 0x3F]
        index += 3
    }
    when (length - index) {
        1 -> {
            val chunk = (source[index].toInt() and 0xFF) shl 16
            destination[output++] = BASE64_ALPHABET[(chunk shr 18) and 0x3F]
            destination[output++] = BASE64_ALPHABET[(chunk shr 12) and 0x3F]
            if (pad) {
                destination[output++] = '='.code.toByte()
                destination[output++] = '='.code.toByte()
            }
        }
        2 -> {
            val chunk = ((source[index].toInt() and 0xFF) shl 16) or
                ((source[index + 1].toInt() and 0xFF) shl 8)
            destination[output++] = BASE64_ALPHABET[(chunk shr 18) and 0x3F]
            destination[output++] = BASE64_ALPHABET[(chunk shr 12) and 0x3F]
            destination[output++] = BASE64_ALPHABET[(chunk shr 6) and 0x3F]
            if (pad) destination[output++] = '='.code.toByte()
        }
    }
    return output
}
