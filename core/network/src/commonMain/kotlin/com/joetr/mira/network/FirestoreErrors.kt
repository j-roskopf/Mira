package com.joetr.mira.network

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val firestoreErrorJson = Json { ignoreUnknownKeys = true }

public fun Throwable.isFirestoreNotFound(): Boolean {
    val client = this as? ClientRequestException ?: return false
    if (client.response.status == HttpStatusCode.NotFound) return true
    return message.orEmpty().contains("NOT_FOUND", ignoreCase = true)
}

public fun Throwable.isFirestoreUnauthenticated(): Boolean {
    val client = this as? ClientRequestException ?: return false
    if (client.response.status == HttpStatusCode.Unauthorized) return true
    return message.orEmpty().contains("UNAUTHENTICATED", ignoreCase = true) ||
        message.orEmpty().contains("invalid authentication credentials", ignoreCase = true)
}

public suspend fun Throwable.firestoreErrorDetail(): String {
    val client = this as? ClientRequestException
    if (client != null) {
        val body = runCatching { client.response.bodyAsText() }.getOrNull()
        val parsed = body?.let { runCatching { firestoreErrorJson.parseToJsonElement(it) }.getOrNull() }
        val message = (parsed as? JsonObject)
            ?.get("error")
            ?.jsonObject
            ?.get("message")
            ?.jsonPrimitive
            ?.contentOrNull
        if (!message.isNullOrBlank()) return message
        if (!body.isNullOrBlank()) return body
    }
    return message ?: "Unknown Firestore error"
}
