package com.joetr.mira.data.archive

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class DeviceCodeRequest(
    val userCode: String,
    val verificationUri: String,
    val deviceCode: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)

public sealed interface DeviceFlowResult {
    public data class Success(val accessToken: String) : DeviceFlowResult
    public data object Expired : DeviceFlowResult
    public data object Denied : DeviceFlowResult
    public data class Failed(val message: String) : DeviceFlowResult
}

/**
 * GitHub's OAuth device flow.
 *
 * Chosen over a redirect flow because it needs **no client secret** and therefore no server: the
 * app shows a code, you type it at github.com/login/device, and that is the whole exchange. It also
 * works identically on all five platforms, including wasm, where a redirect flow would mean
 * handling the return trip through the browser's history.
 */
public class GitHubDeviceFlow(
    private val httpClient: HttpClient,
    private val clientId: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun requestCode(): DeviceCodeRequest? {
        val body = httpClient.post("https://github.com/login/device/code") {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json")
            setBody(
                buildJsonObject {
                    put("client_id", JsonPrimitive(clientId))
                    // `repo` so private repositories work — journal archives should not be public.
                    put("scope", JsonPrimitive("repo"))
                }.toString(),
            )
        }.bodyAsText()

        val fields = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val deviceCode = fields["device_code"]?.jsonPrimitive?.content ?: return null
        val userCode = fields["user_code"]?.jsonPrimitive?.content ?: return null
        return DeviceCodeRequest(
            userCode = userCode,
            verificationUri = fields["verification_uri"]?.jsonPrimitive?.content
                ?: "https://github.com/login/device",
            deviceCode = deviceCode,
            intervalSeconds = fields["interval"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5,
            expiresInSeconds = fields["expires_in"]?.jsonPrimitive?.content?.toIntOrNull() ?: 900,
        )
    }

    /**
     * Polls until the code is entered, honouring GitHub's backoff.
     *
     * `slow_down` means the interval was too aggressive and must be increased, not that anything
     * went wrong — treating it as an error is the usual way device flow implementations break.
     */
    public suspend fun awaitToken(request: DeviceCodeRequest): DeviceFlowResult {
        var intervalMs = request.intervalSeconds * 1000L
        val deadline = request.expiresInSeconds * 1000L
        var waited = 0L

        while (waited < deadline) {
            delay(intervalMs)
            waited += intervalMs

            val body = httpClient.post("https://github.com/login/oauth/access_token") {
                contentType(ContentType.Application.Json)
                header("Accept", "application/json")
                setBody(
                    buildJsonObject {
                        put("client_id", JsonPrimitive(clientId))
                        put("device_code", JsonPrimitive(request.deviceCode))
                        put(
                            "grant_type",
                            JsonPrimitive("urn:ietf:params:oauth:grant-type:device_code"),
                        )
                    }.toString(),
                )
            }.bodyAsText()

            val fields = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: continue

            fields["access_token"]?.jsonPrimitive?.content?.let {
                return DeviceFlowResult.Success(it)
            }

            when (fields["error"]?.jsonPrimitive?.content) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalMs += 5_000L
                "expired_token" -> return DeviceFlowResult.Expired
                "access_denied" -> return DeviceFlowResult.Denied
                null -> Unit
                else -> return DeviceFlowResult.Failed(
                    fields["error_description"]?.jsonPrimitive?.content ?: "Authorization failed",
                )
            }
        }
        return DeviceFlowResult.Expired
    }
}
