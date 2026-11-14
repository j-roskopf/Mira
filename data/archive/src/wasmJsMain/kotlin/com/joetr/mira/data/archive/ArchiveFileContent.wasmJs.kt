package com.joetr.mira.data.archive

/** The browser has no preserved media files; its archive payload comes from bytes. */
internal actual fun openArchiveFileReader(path: String): ArchiveFileReader? = null
