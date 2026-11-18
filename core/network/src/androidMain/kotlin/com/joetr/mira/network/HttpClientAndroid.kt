package com.joetr.mira.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

public actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = true
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    // A stalled request must not leave the archive showing "Backing up" forever. The socket
    // timeout catches a dead connection; the request timeout is generous enough for a large blob.
    install(HttpTimeout) {
        requestTimeoutMillis = 30 * 60 * 1000
        connectTimeoutMillis = 30 * 1000
        socketTimeoutMillis = 2 * 60 * 1000
    }
}

public actual fun createMiraImageHttpClient(): HttpClient = HttpClient(OkHttp)
