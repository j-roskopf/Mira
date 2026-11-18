package com.joetr.mira.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun writeResponseBodyToFile(
    response: HttpResponse,
    path: String,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val directory = File(path).parentFile
        if (directory != null && !directory.exists()) directory.mkdirs()
        val channel = response.bodyAsChannel()
        File(path).outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read < 0) break
                if (read > 0) output.write(buffer, 0, read)
            }
        }
        true
    }.getOrDefault(false)
}
