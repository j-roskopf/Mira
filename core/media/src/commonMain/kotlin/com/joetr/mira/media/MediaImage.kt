package com.joetr.mira.media

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes a photo for display, honouring the EXIF orientation written by phone cameras.
 *
 * Camera JPEGs are usually stored unrotated with an EXIF orientation tag, and a plain Skia decode
 * ignores that tag — which shows a portrait photo on its side. Each platform decodes with its own
 * image stack so the pixels are already upright by the time they reach Compose.
 */
public expect fun decodePhoto(bytes: ByteArray): ImageBitmap?
