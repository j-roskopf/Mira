package com.joetr.mira.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import com.joetr.mira.domain.Attachment
import kotlinx.coroutines.CancellationException

/** Load state of an [AttachmentImage], enough for call sites to render their own chrome. */
public enum class AttachmentImageState {
    Idle,
    Loading,
    Success,
    Error,
}

private sealed interface ResolvedSource {
    data object Resolving : ResolvedSource
    data class Ready(val source: MediaSource?) : ResolvedSource
}

/**
 * Renders a photo attachment through Coil, which owns the memory and disk caches.
 *
 * [cacheKey] separates the same attachment's uses (a small grid tile and a full-screen copy
 * should not evict each other from the memory cache). The bytes themselves are still decoded by
 * [decodePhoto] via [MiraImageDecoderFactory], so every platform keeps its existing EXIF handling.
 */
@Composable
public fun AttachmentImage(
    attachment: Attachment,
    sourceFor: suspend (Attachment) -> MediaSource?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cacheKey: String = "default",
    onState: ((AttachmentImageState) -> Unit)? = null,
) {
    val resolved by produceState<ResolvedSource>(
        initialValue = ResolvedSource.Resolving,
        attachment.id,
        attachment.state,
        attachment.url,
        attachment.storagePath,
        attachment.posterPath,
        attachment.posterUrl,
        attachment.thumbnailPath,
        attachment.thumbnailUrl,
    ) {
        value = ResolvedSource.Resolving
        val source = try {
            sourceFor(attachment)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        value = ResolvedSource.Ready(source)
    }

    when (val current = resolved) {
        ResolvedSource.Resolving -> Unit
        is ResolvedSource.Ready -> {
            val source = current.source
            if (source == null) {
                LaunchedEffect(attachment.id) { onState?.invoke(AttachmentImageState.Error) }
                return
            }

            val context = LocalPlatformContext.current
            val imageLoader = remember(context) { SingletonImageLoader.get(context) }
            val sourceKey = when (source) {
                is MediaSource.Bytes -> "bytes:${source.bytes.size}"
                is MediaSource.LocalFile -> "file:${source.path}"
                is MediaSource.Remote -> "url:${source.url}"
            }
            val request = remember(context, attachment.id, cacheKey, sourceKey) {
                ImageRequest.Builder(context)
                    .data(source.toCoilData())
                    .memoryCacheKey("mira-attachment:${attachment.id}:$cacheKey")
                    // Reuse a larger cached bitmap for a smaller tile instead of re-decoding it.
                    .precision(Precision.INEXACT)
                    .build()
            }

            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                imageLoader = imageLoader,
                modifier = modifier,
                contentScale = contentScale,
                onLoading = { onState?.invoke(AttachmentImageState.Loading) },
                onSuccess = { onState?.invoke(AttachmentImageState.Success) },
                onError = { onState?.invoke(AttachmentImageState.Error) },
            )
        }
    }
}

private fun MediaSource.toCoilData(): Any = when (this) {
    is MediaSource.Bytes -> bytes
    is MediaSource.LocalFile -> Uri(scheme = "file", path = path)
    is MediaSource.Remote -> url
}
