package com.joetr.mira.media

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun readLocalFileBytes(path: String): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching { File(path).readBytes() }.getOrNull()
    }
