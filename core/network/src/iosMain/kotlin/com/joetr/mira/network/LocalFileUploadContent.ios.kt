package com.joetr.mira.network

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent

/** iOS uploads stay in memory; [com.joetr.mira.media.supportsStreamingFileUpload] is false there. */
internal actual fun localFileUploadContent(path: String, contentType: ContentType): OutgoingContent =
    error("Streaming file uploads are not supported on iOS")
