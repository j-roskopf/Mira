package com.joetr.mira.app

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.joetr.mira.data.image.LOCAL_IMAGE_SCHEME
import com.joetr.mira.platform.detectImageMimeType
import com.joetr.mira.platform.isHeicImage
import com.joetr.mira.platform.resizeImage
import com.joetr.mira.domain.repository.ImageRepository
import okio.Buffer

internal class LocalImageUriKeyer : Keyer<String> {
    override fun key(data: String, options: Options): String? =
        data.takeIf { it.startsWith("$LOCAL_IMAGE_SCHEME://") }
}

internal class LocalImageUriFetcher(
    private val imageRepository: ImageRepository,
    private val data: String,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val id = data.removePrefix("$LOCAL_IMAGE_SCHEME://")
        val bytes = imageRepository.readLocalImage(id) ?: return null
        val displayBytes = if (isHeicImage(bytes)) {
            resizeImage(bytes, maxLongEdge = 1600, quality = 80)
        } else {
            bytes
        }
        val source = ImageSource(Buffer().write(displayBytes), options.fileSystem)
        return SourceFetchResult(
            source = source,
            mimeType = detectImageMimeType(displayBytes) ?: "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    internal class Factory(
        private val imageRepository: ImageRepository,
    ) : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.startsWith("$LOCAL_IMAGE_SCHEME://")) return null
            return LocalImageUriFetcher(imageRepository, data, options)
        }
    }
}

internal fun ImageLoader.Builder.miraImages(
    imageRepository: ImageRepository,
): ImageLoader.Builder =
    components {
        add(LocalImageUriKeyer())
        add(LocalImageUriFetcher.Factory(imageRepository))
    }
