package com.joetr.mira.media

import androidx.compose.ui.graphics.ImageBitmap

public actual fun decodePhoto(bytes: ByteArray): ImageBitmap? =
    decodeJpegFrame(bytes)
