package com.joetr.mira.data.archive

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A safe, actionable explanation that can be shown without exposing OAuth response bodies. */
public class GitHubAuthorizationException(message: String) : Exception(message)

private fun authorizationErrorMessage(error: String): String = when (error) {
    "device_flow_disabled" -> "Device flow is disabled for Mira's GitHub OAuth app. " +
        "Enable Device Flow in the app's GitHub settings, save, and try again."
    "incorrect_client_credentials" -> "GitHub did not recognize Mira's OAuth client ID. " +
        "Check the client ID configured for this build."
    else -> "GitHub could not authorize Mira. Please try connecting again."
}

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
        val response: HttpResponse
        val body: String
        try {
            response = httpClient.post("https://github.com/login/device/code") {
                // OAuth errors have useful JSON bodies even when the HTTP status is 400.
                expectSuccess = false
                contentType(ContentType.Application.Json)
                header("Accept", "application/json")
                setBody(
                    buildJsonObject {
                        put("client_id", JsonPrimitive(clientId))
                        // `repo` so private repositories work — journal archives should not be public.
                        put("scope", JsonPrimitive("repo"))
                    }.toString(),
                )
            }
            body = response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw GitHubAuthorizationException(
                "Could not reach GitHub to start authorization. Check your connection and try again.",
            )
        }
        val fields = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        fields?.get("error")?.jsonPrimitive?.content?.let {
            throw GitHubAuthorizationException(authorizationErrorMessage(it))
        }
        if (!response.status.isSuccess()) {
            throw GitHubAuthorizationException("GitHub authorization request failed (HTTP ${response.status.value}). Please try again.")
        }
        if (fields == null) return null
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
        var lastPollReachedGitHub = true

        while (waited < deadline) {
            delay(intervalMs)
            waited += intervalMs

            val outcome = try {
                lastPollReachedGitHub = true
                pollForToken(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Polling can fail transiently — most notably on mobile, where the app is
                // backgrounded while the user enters the code in a browser. The device code stays
                // valid until the deadline, so keep polling instead of ending the whole flow.
                lastPollReachedGitHub = false
                continue
            }

            when (outcome) {
                is PollOutcome.Token -> return DeviceFlowResult.Success(outcome.accessToken)
                is PollOutcome.Terminal -> return outcome.result
                PollOutcome.SlowDown -> intervalMs += 5_000L
                PollOutcome.Pending -> Unit
            }
        }
        return if (lastPollReachedGitHub) {
            DeviceFlowResult.Expired
        } else {
            DeviceFlowResult.Failed(
                "Could not reach GitHub to finish authorization. Check your connection and try again.",
            )
        }
    }

    /** One poll of the token endpoint, parsed into an outcome the polling loop understands. */
    private suspend fun pollForToken(request: DeviceCodeRequest): PollOutcome {
        val response = httpClient.post("https://github.com/login/oauth/access_token") {
            expectSuccess = false
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
        }
        val body = response.bodyAsText()
        val fields = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return PollOutcome.Terminal(
                DeviceFlowResult.Failed("GitHub returned an invalid authorization response."),
            )
        if (!response.status.isSuccess() && fields["error"] == null) {
            return PollOutcome.Terminal(
                DeviceFlowResult.Failed(
                    "GitHub authorization request failed (HTTP ${response.status.value}). Please try again.",
                ),
            )
        }
        fields["access_token"]?.jsonPrimitive?.content?.let { return PollOutcome.Token(it) }
        return when (fields["error"]?.jsonPrimitive?.content) {
            "authorization_pending", null -> PollOutcome.Pending
            "slow_down" -> PollOutcome.SlowDown
            "expired_token" -> PollOutcome.Terminal(DeviceFlowResult.Expired)
            "access_denied" -> PollOutcome.Terminal(DeviceFlowResult.Denied)
            else -> PollOutcome.Terminal(
                DeviceFlowResult.Failed(
                    authorizationErrorMessage(fields["error"]!!.jsonPrimitive.content),
                ),
            )
        }
    }

    private sealed interface PollOutcome {
        data object Pending : PollOutcome
        data object SlowDown : PollOutcome
        data class Token(val accessToken: String) : PollOutcome
        data class Terminal(val result: DeviceFlowResult) : PollOutcome
    }
}
