package com.joetr.mira.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

public actual fun createPlatformHttpClient(): HttpClient = HttpClient(Js) {
    expectSuccess = true
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

public actual fun createMiraImageHttpClient(): HttpClient = HttpClient(Js)
