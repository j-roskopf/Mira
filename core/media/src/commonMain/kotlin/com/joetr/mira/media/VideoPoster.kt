package com.joetr.mira.media

/**
 * Longest edge of a generated video poster.
 *
 * A poster is only ever shown as a tile or a calendar thumbnail, so a small JPEG keeps the upload
 * cheap and the memory footprint of a scrolling gallery low.
 */
public const val VideoPosterMaxLongEdge: Int = 640

/**
 * Extracts a cover frame from a video and returns it as JPEG bytes.
 *
 * Everything is decoded locally — the file path or the staged bytes are already on the device, so
 * no network round trip is needed. [path] is preferred when it points at a readable file; [bytes]
 * covers web (no filesystem) and blob-backed media. Returns null when the platform cannot decode
 * the video or the input is unreadable.
 */
public expect suspend fun extractVideoPoster(
    path: String?,
    bytes: ByteArray?,
    mime: String,
): ByteArray?
