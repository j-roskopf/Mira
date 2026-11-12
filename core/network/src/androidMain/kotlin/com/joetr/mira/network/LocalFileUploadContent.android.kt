package com.joetr.mira.network

import io.ktor.client.content.LocalFileContent
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import java.io.File

internal actual fun localFileUploadContent(path: String, contentType: ContentType): OutgoingContent =
    LocalFileContent(File(path), contentType)
