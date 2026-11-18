package com.joetr.mira.data.archive

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength

/**
 * iOS can read a preserved recording without materialising it, which keeps chunked base64
 * streaming inside a mobile heap. `NSFileHandle` gives that straight from Foundation.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun openArchiveFileReader(path: String): ArchiveFileReader? {
    val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
    val size = (attributes?.get("NSFileSize") as? Number)?.toLong() ?: 0L
    return object : ArchiveFileReader {
        override val size: Long = size

        override fun read(buffer: ByteArray): Int {
            val data = handle.readDataOfLength(buffer.size.toULong())
            val length = data.length.toInt()
            if (length <= 0) return -1
            data.bytes?.reinterpret<kotlinx.cinterop.ByteVar>()?.readBytes(length)?.copyInto(buffer)
            return length
        }

        override fun close() = handle.closeFile()
    }
}
