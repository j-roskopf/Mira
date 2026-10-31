package com.joetr.mira.data.entries.media

import java.io.File

internal actual suspend fun readLocalFile(path: String): ByteArray? =
    File(path).takeIf { it.exists() }?.readBytes()
