package com.joetr.mira.platform

public fun isJpegImage(bytes: ByteArray): Boolean =
    bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()

public fun isPngImage(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() &&
        bytes[3] == 'G'.code.toByte()

public fun isHeicImage(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    if (bytes[4] != 'f'.code.toByte() ||
        bytes[5] != 't'.code.toByte() ||
        bytes[6] != 'y'.code.toByte() ||
        bytes[7] != 'p'.code.toByte()
    ) {
        return false
    }
    val brand = bytes.decodeToString(8, minOf(bytes.size, 32))
    return brand.contains("heic", ignoreCase = true) || brand.contains("mif1", ignoreCase = true)
}

public fun detectImageMimeType(bytes: ByteArray): String? = when {
    isJpegImage(bytes) -> "image/jpeg"
    isPngImage(bytes) -> "image/png"
    isHeicImage(bytes) -> "image/heic"
    bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() &&
        bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() &&
        bytes[11] == 'P'.code.toByte() -> "image/webp"
    else -> null
}
