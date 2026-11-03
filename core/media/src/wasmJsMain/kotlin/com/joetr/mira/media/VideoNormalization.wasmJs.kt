package com.joetr.mira.media

/**
 * The browser already transcodes capture to H.264 with ffmpeg.wasm, and has no path for
 * re-encoding an arbitrary uploaded file, so this is a no-op.
 */
public actual val supportsVideoNormalization: Boolean = false

public actual suspend fun normalizeVideo(bytes: ByteArray, mime: String): ByteArray? = bytes
