package com.joetr.mira.data.entries.media

import com.joetr.mira.platform.AndroidContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal actual suspend fun readLocalFile(path: String): ByteArray? =
    File(path).takeIf { it.exists() }?.readBytes()

internal actual suspend fun preserveLocalFile(path: String): String = withContext(Dispatchers.IO) {
    copyRecordingToStorage(File(path), File(AndroidContextHolder.application.filesDir, "media"))
}

/** Copy before publishing the blob; failure must not leave a playable-looking attachment. */
internal fun copyRecordingToStorage(source: File, directory: File): String {
    check(source.isFile && source.length() > 0) { "The recording file is missing or empty" }
    check(directory.isDirectory || directory.mkdirs()) { "Could not create media storage" }
    val destination = File.createTempFile("recording-", ".${source.extension}", directory)
    try {
        source.copyTo(destination, overwrite = true)
        return destination.absolutePath
    } catch (error: Throwable) {
        destination.delete()
        throw error
    }
}

internal actual suspend fun localFileExists(path: String): Boolean = withContext(Dispatchers.IO) {
    File(path).isFile
}

internal actual suspend fun deletePreservedFile(path: String) {
    withContext(Dispatchers.IO) {
        val directory = File(AndroidContextHolder.application.filesDir, "media")
        val file = File(path)
        if (file.parentFile?.canonicalFile == directory.canonicalFile) file.delete()
    }
}
