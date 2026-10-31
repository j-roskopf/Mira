package com.joetr.mira.data.entries.media

/**
 * The browser has no filesystem path Mira can read back, and web keeps no local media anyway —
 * captured bytes live only in the staging blob until they reach Storage.
 */
internal actual suspend fun readLocalFile(path: String): ByteArray? = null
