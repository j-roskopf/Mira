package com.joetr.mira.media

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes a photo for display, honouring the EXIF orientation written by phone cameras.
 *
 * Camera JPEGs are usually stored unrotated with an EXIF orientation tag, and a plain Skia decode
 * ignores that tag — which shows a portrait photo on its side. Each platform decodes with its own
 * image stack so the pixels are already upright by the time they reach Compose.
 *
 * [maxLongEdge] caps the longer side in pixels so a thumbnail does not keep a 12 MP bitmap alive
 * in the composition (which stalls entry open and makes the attachment list scroll like mud on
 * Android). Pass [Int.MAX_VALUE] for a full-resolution decode.
 */
public expect fun decodePhoto(bytes: ByteArray, maxLongEdge: Int = Int.MAX_VALUE): ImageBitmap?
