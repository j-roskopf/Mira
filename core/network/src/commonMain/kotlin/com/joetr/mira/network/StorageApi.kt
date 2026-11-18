package com.joetr.mira.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import com.joetr.mira.platform.MiraConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Result of putting media into Firebase Storage. */
public data class StorageUpload(
    val storagePath: String,
    val downloadUrl: String,
)

/**
 * The bytes to upload, either already in memory or still on disk.
 *
 * [LocalFile] exists so a large recording can be streamed straight from the file instead of being
 * materialised as a `ByteArray`. Loading a 200 MB video into an Android heap that is capped well
 * below that is an `OutOfMemoryError`, not a failed upload.
 */
public sealed interface UploadBody {
    public data class Bytes(val bytes: ByteArray) : UploadBody

    public data class LocalFile(val path: String) : UploadBody
}

/**
 * A request body that reads from disk as the HTTP engine consumes it.
 *
 * Only used by platforms that advertise streaming uploads; the others keep using [UploadBody.Bytes].
 */
internal expect fun localFileUploadContent(path: String, contentType: ContentType): OutgoingContent

public class StorageApi(
    private val httpClient: HttpClient,
) {
    private val bucket: String get() = MiraConfig.FIREBASE_STORAGE_BUCKET
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Object path for one attachment.
     *
     * Partitioned by entry so deleting an entry's media is a prefix operation, and so an orphan
     * sweep can tell at a glance which entry an object belonged to.
     */
    public fun mediaPath(ownerId: String, entryId: String, attachmentId: String, extension: String): String =
        "users/$ownerId/media/$entryId/$attachmentId.$extension"

    public suspend fun upload(
        idToken: String,
        objectName: String,
        bytes: ByteArray,
        mime: String,
    ): StorageUpload = upload(idToken, objectName, UploadBody.Bytes(bytes), mime)

    public suspend fun upload(
        idToken: String,
        objectName: String,
        body: UploadBody,
        mime: String,
    ): StorageUpload {
        val encoded = objectName.encodeURLPathComponent()
        val contentType = ContentType.parse(mime)
        val responseText = httpClient.post(
            "https://firebasestorage.googleapis.com/v0/b/$bucket/o?uploadType=media&name=$encoded",
        ) {
            contentType(contentType)
            header("Authorization", "Bearer $idToken")
            setBody(
                when (body) {
                    is UploadBody.Bytes -> ByteArrayContent(body.bytes)
                    is UploadBody.LocalFile -> localFileUploadContent(body.path, contentType)
                },
            )
        }.bodyAsText()
        val metadata = json.parseToJsonElement(responseText).jsonObject
        val token = metadata["downloadTokens"]?.jsonPrimitive?.content
            ?: error("Storage upload missing downloadTokens")
        return StorageUpload(objectName, downloadUrl(objectName, token))
    }

    public suspend fun download(idToken: String, objectName: String): ByteArray {
        val encoded = objectName.encodeURLPathComponent()
        return httpClient.get(
            "https://firebasestorage.googleapis.com/v0/b/$bucket/o/$encoded?alt=media",
        ) {
            header("Authorization", "Bearer $idToken")
        }.bodyAsBytes()
    }

    /**
     * Downloads an object straight to [path], one chunk at a time.
     *
     * The archive needs the bytes of a recording whose local copy was evicted or never existed on
     * this device. `download` returns a `ByteArray`, which for a 75 MB video is already enough to
     * exhaust a mobile heap before anything is uploaded. Writing to disk keeps the peak tiny and
     * lets the archive stream the file into GitHub afterwards.
     */
    public suspend fun downloadToFile(
        idToken: String,
        objectName: String,
        path: String,
    ): Boolean {
        val encoded = objectName.encodeURLPathComponent()
        return runCatching {
            httpClient.prepareGet(
                "https://firebasestorage.googleapis.com/v0/b/$bucket/o/$encoded?alt=media",
            ) {
                header("Authorization", "Bearer $idToken")
                expectSuccess = false
            }.execute { response ->
                response.status.isSuccess() && writeResponseBodyToFile(response, path)
            }
        }.getOrDefault(false)
    }

    public suspend fun deleteObject(idToken: String, objectName: String) {
        val encoded = objectName.encodeURLPathComponent()
        httpClient.delete("https://firebasestorage.googleapis.com/v0/b/$bucket/o/$encoded") {
            header("Authorization", "Bearer $idToken")
        }
    }

    /**
     * Lists every object under [prefix].
     *
     * Used by the orphan sweep in Settings: with no Cloud Functions, nothing server-side reclaims
     * media whose entry was deleted while the client was offline, so the client has to be able to
     * enumerate what is actually in the bucket.
     */
    public suspend fun list(idToken: String, prefix: String, pageSize: Int = 1000): List<String> {
        val encoded = prefix.encodeURLPathComponent()
        val responseText = httpClient.get(
            "https://firebasestorage.googleapis.com/v0/b/$bucket/o?prefix=$encoded&maxResults=$pageSize",
        ) {
            header("Authorization", "Bearer $idToken")
        }.bodyAsText()
        val items = json.parseToJsonElement(responseText).jsonObject["items"] ?: return emptyList()
        return items.let { element ->
            (element as? kotlinx.serialization.json.JsonArray)?.mapNotNull { item ->
                item.jsonObject["name"]?.jsonPrimitive?.content
            } ?: emptyList()
        }
    }

    public fun downloadUrl(objectName: String, token: String): String {
        val encoded = objectName.encodeURLPathComponent()
        return "https://firebasestorage.googleapis.com/v0/b/$bucket/o/$encoded?alt=media&token=$token"
    }
}

/** Platform file writer for [downloadToFile]; false when the platform has no filesystem. */
internal expect suspend fun writeResponseBodyToFile(response: HttpResponse, path: String): Boolean

internal fun String.encodeURLPathComponent(): String = buildString {
    for (ch in this@encodeURLPathComponent) {
        when {
            ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> append(ch)
            ch == '/' -> append("%2F")
            else -> append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
