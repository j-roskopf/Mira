package com.joetr.mira.network

import io.ktor.client.statement.HttpResponse

/** The browser has no filesystem the archive can stream from. */
internal actual suspend fun writeResponseBodyToFile(
    response: HttpResponse,
    path: String,
): Boolean = false
