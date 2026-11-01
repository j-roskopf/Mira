package com.joetr.mira.data.entries.media

import java.io.File
import com.joetr.mira.platform.desktopDataDirectoryName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun readLocalFile(path: String): ByteArray? =
    File(path).takeIf { it.exists() }?.readBytes()

internal actual suspend fun preserveLocalFile(path: String): String = withContext(Dispatchers.IO) {
    copyRecordingToStorage(File(path), mediaDirectory())
}

private fun mediaDirectory(): File =
    File(File(System.getProperty("user.home"), desktopDataDirectoryName()), "media")

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

internal actual suspend fun localFileExists(path: String): Boolean = File(path).isFile

internal actual suspend fun deletePreservedFile(path: String) {
    withContext(Dispatchers.IO) {
        val file = File(path)
        if (file.parentFile?.canonicalFile == mediaDirectory().canonicalFile) file.delete()
    }
}
