package com.joetr.mira.media

/**
 * Whether this platform can re-encode a video to H.264 before it is uploaded.
 *
 * Only desktop bundles an encoder capable of doing this. Android, iOS, and the browser have no
 * transcode pipeline, so they upload whatever the capture stack produced.
 */
public expect val supportsVideoNormalization: Boolean

/**
 * Re-encodes [bytes] to an mp4/H.264 stream when it is HEVC, or returns it untouched when it is
 * already portable.
 *
 * HEVC was briefly used for capture on Apple hardware, but Android and browser clients cannot be
 * relied on to decode it. Storage has no server-side transcode step, so the only place to make the
 * archive portable is here, on the way up. Returns null when the platform could not do the work.
 */
public expect suspend fun normalizeVideo(bytes: ByteArray, mime: String): ByteArray?

/** MP4 sample-entry fourCCs that mean this file's video track needs an HEVC decoder. */
public fun isHevcVideo(bytes: ByteArray): Boolean =
    bytes.containsFourCc("hvc1") || bytes.containsFourCc("hev1")

private fun ByteArray.containsFourCc(value: String): Boolean {
    if (value.length != 4 || size < 4) return false
    val a = value[0].code.toByte()
    val b = value[1].code.toByte()
    val c = value[2].code.toByte()
    val d = value[3].code.toByte()
    for (index in 0..size - 4) {
        if (this[index] == a && this[index + 1] == b && this[index + 2] == c && this[index + 3] == d) {
            return true
        }
    }
    return false
}
