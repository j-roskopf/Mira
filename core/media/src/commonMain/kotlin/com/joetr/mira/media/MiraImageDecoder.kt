package com.joetr.mira.media

import androidx.compose.ui.graphics.ImageBitmap
import coil3.Image
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Size
import okio.use

/**
 * A Coil decoder that routes through [decodePhoto].
 *
 * Coil's stock decoders are fine on Android, but the Skia decoders used on desktop/iOS/web do not
 * apply EXIF orientation. Going through [decodePhoto] keeps the per-platform behaviour that was
 * already in place while letting Coil own fetching, memory caching, and the network disk cache.
 */
public fun MiraImageDecoderFactory(): Decoder.Factory = Decoder.Factory { result, options, _ ->
    MiraPhotoDecoder(result, options)
}

private class MiraPhotoDecoder(
    private val result: SourceFetchResult,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = result.source.source().use { it.readByteArray() }
        val maxLongEdge = options.size.maxLongEdgePx()
        val bitmap = decodePhoto(bytes, maxLongEdge)
            ?: error("Unable to decode image attachment")
        return DecodeResult(
            image = bitmap.toCoilImage(),
            isSampled = maxLongEdge != Int.MAX_VALUE,
        )
    }
}

private fun Size.maxLongEdgePx(): Int {
    val widthPx = (width as? Dimension.Pixels)?.px ?: 0
    val heightPx = (height as? Dimension.Pixels)?.px ?: 0
    return maxOf(widthPx, heightPx).takeIf { it > 0 } ?: Int.MAX_VALUE
}

internal expect fun ImageBitmap.toCoilImage(): Image
