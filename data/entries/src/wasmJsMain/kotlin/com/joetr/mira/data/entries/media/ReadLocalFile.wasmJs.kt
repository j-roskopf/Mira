package com.joetr.mira.data.entries.media

/**
 * The browser has no filesystem path Mira can read back, and web keeps no local media anyway —
 * captured bytes live only in the staging blob until they reach Storage.
 */
internal actual suspend fun readLocalFile(path: String): ByteArray? = null

internal actual suspend fun preserveLocalFile(path: String): String = path

internal actual suspend fun localFileExists(path: String): Boolean = false

internal actual suspend fun deletePreservedFile(path: String) = Unit
