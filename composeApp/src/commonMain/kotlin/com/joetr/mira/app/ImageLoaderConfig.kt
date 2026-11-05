package com.joetr.mira.app

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.joetr.mira.media.MiraImageDecoderFactory
import com.joetr.mira.network.createMiraImageHttpClient

/**
 * Installs the process-wide Coil loader.
 *
 * Must run before the first [coil3.compose.AsyncImage] composes. Images use the same kept-alive
 * Ktor client as the rest of the app so connection pooling is shared, and Coil's default memory
 * and disk caches handle repeated attachment views.
 */
internal fun configureImageLoader() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = { createMiraImageHttpClient() }))
                add(MiraImageDecoderFactory())
            }
            .crossfade(true)
            .build()
    }
}
