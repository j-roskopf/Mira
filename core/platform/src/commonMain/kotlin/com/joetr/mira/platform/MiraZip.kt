package com.joetr.mira.platform

/**
 * A minimal, dependency-free ZIP reader/writer.
 *
 * Entries are stored uncompressed (`STORE`) — commonMain has no portable deflate implementation,
 * and an export bundle is mostly audio/video that's already compressed, so little would be saved.
 * [read] only understands archives [write] produced (or any other STORE-only, non-ZIP64 archive);
 * it exists to round-trip Mira's own export files, not as a general-purpose unzip.
 */
public object MiraZip {
    private const val LOCAL_FILE_SIGNATURE = 0x04034b50
    private const val CENTRAL_DIR_SIGNATURE = 0x02014b50
    private const val END_OF_CENTRAL_DIR_SIGNATURE = 0x06054b50
    private const val STORE_METHOD = 0

    // 1980-01-01, the minimum valid MS-DOS date — these archives aren't opened by date.
    private const val DOS_DATE = 0x21
    private const val DOS_TIME = 0

    public fun write(entries: List<Pair<String, ByteArray>>): ByteArray {
        val buffer = GrowableByteBuffer()
        val offsets = IntArray(entries.size)

        entries.forEachIndexed { index, (name, data) ->
            offsets[index] = buffer.size
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)

            buffer.writeInt32(LOCAL_FILE_SIGNATURE)
            buffer.writeInt16(20) // version needed
            buffer.writeInt16(0) // flags
            buffer.writeInt16(STORE_METHOD)
            buffer.writeInt16(DOS_TIME)
            buffer.writeInt16(DOS_DATE)
            buffer.writeInt32(crc)
            buffer.writeInt32(data.size)
            buffer.writeInt32(data.size)
            buffer.writeInt16(nameBytes.size)
            buffer.writeInt16(0) // extra field length
            buffer.writeBytes(nameBytes)
            buffer.writeBytes(data)
        }

        val centralDirStart = buffer.size
        entries.forEachIndexed { index, (name, data) ->
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)

            buffer.writeInt32(CENTRAL_DIR_SIGNATURE)
            buffer.writeInt16(20) // version made by
            buffer.writeInt16(20) // version needed
            buffer.writeInt16(0) // flags
            buffer.writeInt16(STORE_METHOD)
            buffer.writeInt16(DOS_TIME)
            buffer.writeInt16(DOS_DATE)
            buffer.writeInt32(crc)
            buffer.writeInt32(data.size)
            buffer.writeInt32(data.size)
            buffer.writeInt16(nameBytes.size)
            buffer.writeInt16(0) // extra field length
            buffer.writeInt16(0) // comment length
            buffer.writeInt16(0) // disk number start
            buffer.writeInt16(0) // internal attrs
            buffer.writeInt32(0) // external attrs
            buffer.writeInt32(offsets[index])
            buffer.writeBytes(nameBytes)
        }
        val centralDirSize = buffer.size - centralDirStart

        buffer.writeInt32(END_OF_CENTRAL_DIR_SIGNATURE)
        buffer.writeInt16(0) // disk number
        buffer.writeInt16(0) // disk with central dir
        buffer.writeInt16(entries.size)
        buffer.writeInt16(entries.size)
        buffer.writeInt32(centralDirSize)
        buffer.writeInt32(centralDirStart)
        buffer.writeInt16(0) // comment length

        return buffer.toByteArray()
    }

    public fun read(bytes: ByteArray): Map<String, ByteArray> {
        val eocdOffset = findEndOfCentralDirectory(bytes)
            ?: throw IllegalArgumentException("Not a Mira export file: no end-of-central-directory record found.")
        val reader = ByteReader(bytes, eocdOffset)
        reader.skip(4) // signature
        reader.skip(2) // disk number
        reader.skip(2) // disk with central dir
        reader.skip(2) // total entries on this disk
        val totalEntries = reader.readInt16()
        reader.skip(4) // central dir size
        val centralDirOffset = reader.readInt32()

        val result = LinkedHashMap<String, ByteArray>(totalEntries)
        var pointer = centralDirOffset
        repeat(totalEntries) {
            val entryReader = ByteReader(bytes, pointer)
            val signature = entryReader.readInt32()
            require(signature == CENTRAL_DIR_SIGNATURE) { "Corrupt Mira export file: bad central directory entry." }
            entryReader.skip(4) // version made by + version needed
            entryReader.skip(2) // flags
            val method = entryReader.readInt16()
            entryReader.skip(4) // mod time + mod date
            entryReader.skip(4) // crc32
            val compressedSize = entryReader.readInt32()
            entryReader.skip(4) // uncompressed size
            val nameLength = entryReader.readInt16()
            val extraLength = entryReader.readInt16()
            val commentLength = entryReader.readInt16()
            entryReader.skip(2) // disk number start
            entryReader.skip(2) // internal attrs
            entryReader.skip(4) // external attrs
            val localHeaderOffset = entryReader.readInt32()
            val name = entryReader.readString(nameLength)

            require(method == STORE_METHOD) {
                "Unsupported Mira export file: entry \"$name\" is compressed. " +
                    "Only uncompressed Mira export files can be imported."
            }
            result[name] = readLocalFileData(bytes, localHeaderOffset, compressedSize)

            pointer = entryReader.position + extraLength + commentLength
        }
        return result
    }

    private fun readLocalFileData(bytes: ByteArray, localHeaderOffset: Int, dataSize: Int): ByteArray {
        val reader = ByteReader(bytes, localHeaderOffset)
        val signature = reader.readInt32()
        require(signature == LOCAL_FILE_SIGNATURE) { "Corrupt Mira export file: bad local file header." }
        reader.skip(2 + 2 + 2 + 2 + 2) // version, flags, method, mod time, mod date
        reader.skip(4 + 4 + 4) // crc32, compressed size, uncompressed size
        val nameLength = reader.readInt16()
        val extraLength = reader.readInt16()
        val dataStart = reader.position + nameLength + extraLength
        return bytes.copyOfRange(dataStart, dataStart + dataSize)
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int? {
        // No archive comment is ever written, so the record is always the last 22 bytes — but
        // scan backwards a little further in case a reader appended trailing bytes.
        val minSize = 22
        if (bytes.size < minSize) return null
        val searchFloor = maxOf(0, bytes.size - minSize - 0xFFFF)
        for (offset in (bytes.size - minSize) downTo searchFloor) {
            if (readInt32LE(bytes, offset) == END_OF_CENTRAL_DIR_SIGNATURE) return offset
        }
        return null
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private val crcTable: IntArray by lazy {
        IntArray(256) { index ->
            var crc = index
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor -0x12477ce0 else crc ushr 1
            }
            crc
        }
    }

    private fun crc32(data: ByteArray): Int {
        var crc = -1
        for (byte in data) {
            crc = crcTable[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv()
    }
}

/** A growable byte buffer — commonMain has no `ByteArrayOutputStream`. */
private class GrowableByteBuffer {
    private var data = ByteArray(8192)
    var size: Int = 0
        private set

    fun writeBytes(bytes: ByteArray) {
        ensureCapacity(size + bytes.size)
        bytes.copyInto(data, size)
        size += bytes.size
    }

    fun writeInt16(value: Int) {
        ensureCapacity(size + 2)
        data[size] = (value and 0xFF).toByte()
        data[size + 1] = ((value ushr 8) and 0xFF).toByte()
        size += 2
    }

    fun writeInt32(value: Int) {
        ensureCapacity(size + 4)
        data[size] = (value and 0xFF).toByte()
        data[size + 1] = ((value ushr 8) and 0xFF).toByte()
        data[size + 2] = ((value ushr 16) and 0xFF).toByte()
        data[size + 3] = ((value ushr 24) and 0xFF).toByte()
        size += 4
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var newSize = data.size * 2
        while (newSize < required) newSize *= 2
        data = data.copyOf(newSize)
    }
}

private class ByteReader(private val bytes: ByteArray, startPosition: Int) {
    var position: Int = startPosition
        private set

    fun skip(count: Int) {
        position += count
    }

    fun readInt16(): Int {
        val value = (bytes[position].toInt() and 0xFF) or ((bytes[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return value
    }

    fun readInt32(): Int {
        val value = (bytes[position].toInt() and 0xFF) or
            ((bytes[position + 1].toInt() and 0xFF) shl 8) or
            ((bytes[position + 2].toInt() and 0xFF) shl 16) or
            ((bytes[position + 3].toInt() and 0xFF) shl 24)
        position += 4
        return value
    }

    fun readString(length: Int): String {
        val value = bytes.decodeToString(position, position + length)
        position += length
        return value
    }
}
