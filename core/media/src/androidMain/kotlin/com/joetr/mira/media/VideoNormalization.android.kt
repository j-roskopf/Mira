package com.joetr.mira.media

/**
 * Android hands captured video to the platform encoder and has no bundled transcoder, so uploads
 * leave the device exactly as recorded. Capture itself only ever asks for H.264.
 */
public actual val supportsVideoNormalization: Boolean = false

public actual suspend fun normalizeVideo(bytes: ByteArray, mime: String): ByteArray? = bytes
