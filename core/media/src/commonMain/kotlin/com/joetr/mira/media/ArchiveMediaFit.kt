package com.joetr.mira.media

/**
 * Asks the platform to make one recording fit the GitHub archive.
 *
 * GitHub's Git Data API rejects request bodies above roughly 40 MiB, and base64 adds a third, so one
 * blob can only carry about 30 MiB. Rather than skipping anything larger, the platform first
 * re-encodes down to [targetBytes] and, only when that cannot get under the limit, splits the bytes
 * into parts no larger than [partLimitBytes]. A split is lossless: concatenating the parts in order
 * reproduces the original file exactly.
 */
public data class ArchiveFitRequest(
    /** Local file when the platform can stream one; null when only [bytes] is available. */
    val path: String?,
    val bytes: ByteArray?,
    val mime: String,
    val durationMs: Long,
    val targetBytes: Long,
    val hardLimitBytes: Long,
    val partLimitBytes: Long,
)

/** One file of a split recording, already written somewhere the archive can stream from. */
public data class ArchiveFitPart(
    val path: String?,
    val bytes: ByteArray?,
    val sizeBytes: Long,
)

public sealed interface ArchiveFitResult {
    /** Frees any temporary files this result owns. Safe to call more than once. */
    public fun cleanup()

    /** A single recording that already fits, or a re-encoded replacement. */
    public class Whole(
        val path: String?,
        val bytes: ByteArray?,
        val sizeBytes: Long,
        val transcoded: Boolean,
        private val onCleanup: () -> Unit = {},
    ) : ArchiveFitResult {
        override fun cleanup() = onCleanup()
    }

    /** A recording stored as ordered parts because it could not be reduced below the limit. */
    public class Split(
        val parts: List<ArchiveFitPart>,
        private val onCleanup: () -> Unit = {},
    ) : ArchiveFitResult {
        override fun cleanup() = onCleanup()
    }

    /** The platform has no encoder for this media; the caller should fall back to splitting. */
    public data object Unsupported : ArchiveFitResult {
        override fun cleanup() {}
    }

    /** Encoding or splitting failed; the source is unchanged. */
    public data object Failed : ArchiveFitResult {
        override fun cleanup() {}
    }
}

/** Whether [mime] can be reduced by this platform before it reaches the archive. */
public expect fun archiveFitSupported(mime: String): Boolean

/**
 * Re-encodes, or splits, [ArchiveFitRequest] for the archive.
 *
 * Implementations must never upscale and must preserve playability: an unsplit result is still an
 * mp4/m4a the rest of the app can decode, and split parts are byte ranges of one such file.
 */
public expect suspend fun fitArchiveMedia(request: ArchiveFitRequest): ArchiveFitResult
