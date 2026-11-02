package com.joetr.mira.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun readLocalFileBytes(path: String): ByteArray? =
    withContext(Dispatchers.Default) {
        NSData.dataWithContentsOfURL(NSURL.fileURLWithPath(path))?.toByteArray()
    }
