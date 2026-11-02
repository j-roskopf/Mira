package com.joetr.mira.media

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Web decode path. [maxLongEdge] is accepted for API parity; Skia on wasm does not expose the
 * same subsampled decode we use on Android, and browser camera captures are already modest.
 */
public actual fun decodePhoto(bytes: ByteArray, maxLongEdge: Int): ImageBitmap? =
    runCatching { bytes.decodeToImageBitmap() }.getOrNull()
