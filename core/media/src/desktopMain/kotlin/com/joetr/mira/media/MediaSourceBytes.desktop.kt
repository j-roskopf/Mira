package com.joetr.mira.media

import java.io.File

internal actual suspend fun readLocalFileBytes(path: String): ByteArray? =
    runCatching { File(path).readBytes() }.getOrNull()
