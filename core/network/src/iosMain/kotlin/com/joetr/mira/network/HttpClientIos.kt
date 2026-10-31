package com.joetr.mira.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

public actual fun createPlatformHttpClient(): HttpClient = HttpClient(Darwin) {
    expectSuccess = true
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

public actual fun createMiraImageHttpClient(): HttpClient = HttpClient(Darwin)
