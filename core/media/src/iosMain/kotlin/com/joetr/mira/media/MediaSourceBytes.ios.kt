package com.joetr.mira.media

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun readLocalFileBytes(path: String): ByteArray? =
    NSData.dataWithContentsOfURL(NSURL.fileURLWithPath(path))?.toByteArray()
