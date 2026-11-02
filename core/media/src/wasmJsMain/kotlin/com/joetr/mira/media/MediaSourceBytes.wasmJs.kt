package com.joetr.mira.media

/** Web never caches media locally, so a [MediaSource.LocalFile] cannot occur here. */
internal actual suspend fun readLocalFileBytes(path: String): ByteArray? = null
