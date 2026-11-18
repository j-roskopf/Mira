package com.joetr.mira.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.writeData

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun writeResponseBodyToFile(
    response: HttpResponse,
    path: String,
): Boolean = withContext(Dispatchers.Default) {
    runCatching {
        NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null)
        val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: return@runCatching false
        try {
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read < 0) break
                if (read == 0) continue
                handle.writeData(buffer.toNSData(read))
            }
            true
        } finally {
            handle.closeFile()
        }
    }.getOrDefault(false)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(length: Int): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(copyOf(length)), length = length.toULong())
}
