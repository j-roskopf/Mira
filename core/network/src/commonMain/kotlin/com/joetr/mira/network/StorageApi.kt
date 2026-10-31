package com.joetr.mira.network

import com.joetr.mira.platform.MiraConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public class StorageApi(
    private val httpClient: HttpClient,
) {
    private val bucket: String get() = MiraConfig.FIREBASE_STORAGE_BUCKET
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun uploadJpeg(
        idToken: String,
        ownerId: String,
        recipeId: String,
        bytes: ByteArray,
    ): String {
        val objectName = "users/$ownerId/$recipeId.jpg"
        val encoded = objectName.encodeURLPathComponent()
        val responseText = httpClient.post(
            "https://firebasestorage.googleapis.com/v0/b/$bucket/o?uploadType=media&name=$encoded",
        ) {
            contentType(ContentType.Image.JPEG)
            header("Authorization", "Bearer $idToken")
            setBody(bytes)
        }.bodyAsText()
        val metadata = json.parseToJsonElement(responseText).jsonObject
        val token = metadata["downloadTokens"]?.jsonPrimitive?.content
            ?: error("Storage upload missing downloadTokens")
        return downloadUrl(objectName, token)
    }

    public suspend fun deleteObject(
        idToken: String,
        ownerId: String,
        recipeId: String,
    ) {
        val objectName = "users/$ownerId/$recipeId.jpg"
        val encoded = objectName.encodeURLPathComponent()
        httpClient.delete("https://firebasestorage.googleapis.com/v0/b/$bucket/o/$encoded") {
            header("Authorization", "Bearer $idToken")
        }
    }

    public fun downloadUrl(objectName: String, token: String): String {
        val encoded = objectName.encodeURLPathComponent()
        return "https://firebasestorage.googleapis.com/v0/b/$bucket/o/$encoded?alt=media&token=$token"
    }
}

internal fun String.encodeURLPathComponent(): String = buildString {
    for (ch in this@encodeURLPathComponent) {
        when {
            ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> append(ch)
            ch == '/' -> append("%2F")
            else -> append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
