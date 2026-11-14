package com.joetr.mira.data.archive

import java.io.File
import java.io.FileInputStream

internal actual fun openArchiveFileReader(path: String): ArchiveFileReader? {
    val file = File(path)
    if (!file.isFile) return null
    val stream = FileInputStream(file)
    return object : ArchiveFileReader {
        override val size: Long = file.length()
        override fun read(buffer: ByteArray): Int = stream.read(buffer)
        override fun close() = stream.close()
    }
}
