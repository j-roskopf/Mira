package com.joetr.mira.network

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent

/** The browser has no local media files; web uploads come from bytes. */
internal actual fun localFileUploadContent(path: String, contentType: ContentType): OutgoingContent =
    error("Streaming file uploads are not supported on web")
