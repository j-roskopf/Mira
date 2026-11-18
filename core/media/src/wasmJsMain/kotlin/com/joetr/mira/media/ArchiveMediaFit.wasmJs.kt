package com.joetr.mira.media

/**
 * The browser has no local file to stream and no always-available encoder; capture already keeps
 * recordings well inside the blob ceiling. Media that is still too large is split losslessly.
 */
public actual fun archiveFitSupported(mime: String): Boolean = false

public actual suspend fun fitArchiveMedia(request: ArchiveFitRequest): ArchiveFitResult {
    val bytes = request.bytes ?: return ArchiveFitResult.Failed
    if (bytes.size.toLong() <= request.targetBytes) {
        return ArchiveFitResult.Whole(null, bytes, bytes.size.toLong(), transcoded = false)
    }
    val limit = request.partLimitBytes.coerceAtLeast(1).toInt()
    val parts = mutableListOf<ArchiveFitPart>()
    var offset = 0
    while (offset < bytes.size) {
        val end = minOf(offset + limit, bytes.size)
        val part = bytes.copyOfRange(offset, end)
        parts += ArchiveFitPart(null, part, part.size.toLong())
        offset = end
    }
    return if (parts.isEmpty()) ArchiveFitResult.Failed else ArchiveFitResult.Split(parts)
}
