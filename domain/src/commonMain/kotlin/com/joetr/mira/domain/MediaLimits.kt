package com.joetr.mira.domain

/**
 * Largest recording a single archive blob can carry.
 *
 * GitHub's documented repository limit is 100 MiB per file, but the Git Data API rejects requests
 * whose JSON body is above roughly 40 MiB (undocumented). Base64 expands bytes by 4/3, so the real
 * ceiling for one blob is about 30 MiB. We use 28 MiB to leave room for JSON framing, and split
 * anything larger into parts.
 */
public const val GITHUB_ARCHIVE_MAX_MEDIA_BYTES: Long = 28L * 1024 * 1024

/**
 * What a re-encode aims for.
 *
 * Four megabytes of headroom below [GITHUB_ARCHIVE_MAX_MEDIA_BYTES] absorbs container overhead and
 * encoder overshoot, so a "fits" result really does fit on the first try.
 */
public const val GITHUB_ARCHIVE_TARGET_MEDIA_BYTES: Long = 24L * 1024 * 1024

/**
 * Largest part a recording is split into when it still exceeds the single-blob limit.
 *
 * Byte splitting is lossless and keeps every request comfortably under the API payload limit; it is
 * used when re-encoding failed to bring the recording under target.
 */
public const val GITHUB_ARCHIVE_PART_MAX_BYTES: Long = 28L * 1024 * 1024

/**
 * Largest recording the archive will try to read at all.
 *
 * A re-encode or split can handle files well past one blob, but a multi-gigabyte source is not worth
 * reading: compression would take too long and the result would be unusable.
 */
public const val GITHUB_ARCHIVE_MAX_SOURCE_BYTES: Long = 2L * 1024 * 1024 * 1024

/**
 * Largest payload held in memory when a platform cannot stream to or from a file.
 *
 * The archive normally downloads and reads from disk. This ceiling only applies to the in-memory
 * fallback, where holding much more than this risks the same out-of-memory failure streaming avoids.
 */
public const val GITHUB_ARCHIVE_MAX_IN_MEMORY_BYTES: Long = 100L * 1024 * 1024
