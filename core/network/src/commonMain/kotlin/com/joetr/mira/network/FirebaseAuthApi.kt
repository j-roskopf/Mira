package com.joetr.mira.network

import com.joetr.mira.platform.MiraConfig
import com.joetr.mira.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.parametersOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer

public class FirebaseAuthApi(
    private val httpClient: HttpClient,
) {
    private val apiKey: String get() = MiraConfig.FIREBASE_API_KEY
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
    private val authRequestJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    public suspend fun signInAnonymously(): FirebaseSession {
        return postAuthSession("accounts:signUp", AnonymousSignUpRequest(), isAnonymous = true)
    }

    public suspend fun signUpWithEmail(email: String, password: String): FirebaseSession {
        return postAuthSession("accounts:signUp", EmailPasswordRequest(email = email, password = password), isAnonymous = false)
    }

    /** Links email/password to an anonymous session on the same Firebase uid. */
    public suspend fun upgradeAnonymousWithEmail(
        idToken: String,
        email: String,
        password: String,
    ): FirebaseSession {
        return postAuthSession(
            method = "accounts:signUp",
            body = LinkAnonymousEmailRequest(
                idToken = idToken,
                email = email,
                password = password,
            ),
            isAnonymous = false,
        )
    }

    public suspend fun signInWithPassword(email: String, password: String): FirebaseSession {
        return postAuthSession(
            method = "accounts:signInWithPassword",
            body = EmailPasswordRequest(email = email, password = password),
            isAnonymous = false,
        )
    }

    public suspend fun signInWithCustomToken(customToken: String): FirebaseSession {
        return postAuthSession(
            method = "accounts:signInWithCustomToken",
            body = CustomTokenRequest(token = customToken),
            isAnonymous = false,
        )
    }

    /**
     * Exchanges a Google credential via Identity Toolkit `accounts:signInWithIdp`.
     *
     * Prefer [pendingToken] (from a prior [SignInWithIdpResult.NeedsConfirmation]) when
     * retrying after an anonymous-link conflict — the original Google ID token is often
     * already consumed. Matches the Firebase JS SDK: pendingToken is sent as a top-level
     * field with no `postBody`.
     */
    public suspend fun signInWithGoogleIdToken(
        idToken: String? = null,
        accessToken: String? = null,
        pendingToken: String? = null,
        existingIdToken: String? = null,
        returnIdpCredential: Boolean = true,
    ): SignInWithIdpResult {
        require(!pendingToken.isNullOrBlank() || !idToken.isNullOrBlank() || !accessToken.isNullOrBlank()) {
            "Google sign-in requires an id token, access token, or pending token"
        }

        val postBody = if (!pendingToken.isNullOrBlank()) {
            null
        } else {
            buildString {
                if (!idToken.isNullOrBlank()) {
                    append("id_token=").append(idToken.encodeURLParameter())
                }
                if (!accessToken.isNullOrBlank()) {
                    if (isNotEmpty()) append('&')
                    append("access_token=").append(accessToken.encodeURLParameter())
                }
                if (isNotEmpty()) append('&')
                append("providerId=google.com")
            }
        }

        val requestBody = json.encodeToString(
            SignInWithIdpRequest.serializer(),
            SignInWithIdpRequest(
                postBody = postBody,
                requestUri = "http://localhost",
                idToken = existingIdToken,
                pendingToken = pendingToken?.takeIf { it.isNotBlank() },
                returnIdpCredential = returnIdpCredential,
                returnSecureToken = true,
            ),
        )
        val responseText = httpClient.post(identityUrl("accounts:signInWithIdp")) {
            setBody(TextContent(requestBody, ContentType.Application.Json))
        }.bodyAsText()

        val root = json.parseToJsonElement(responseText).jsonObject
        parseIdentityToolkitError(root)?.let { code ->
            error("Firebase Auth error: $code")
        }

        val sessionIdToken = root.string("idToken")
        val refreshToken = root.string("refreshToken")
        val localId = root.string("localId")
        val email = root.string("email")
        val oauthIdToken = root.string("oauthIdToken")
        val oauthAccessToken = root.string("oauthAccessToken")
        val pending = root.string("pendingToken")
        val errorMessage = root.string("errorMessage")
        val needConfirmation = root["needConfirmation"]?.jsonPrimitive?.booleanOrNull == true
        val verifiedProviders = root.stringList("verifiedProvider")
        val expiresIn = root["expiresIn"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: root["expiresIn"]?.jsonPrimitive?.longOrNull
            ?: 3600L

        val signedIn = !sessionIdToken.isNullOrBlank() &&
            !refreshToken.isNullOrBlank() &&
            !localId.isNullOrBlank() &&
            !needConfirmation &&
            errorMessage.isNullOrBlank()

        if (signedIn) {
            return SignInWithIdpResult.Success(
                FirebaseSession(
                    localId = localId,
                    email = email,
                    idToken = sessionIdToken,
                    refreshToken = refreshToken,
                    expiresAtEpochMs = currentTimeMillis() + expiresIn * 1000L,
                    isAnonymous = false,
                ),
            )
        }

        val credentialConflict = needConfirmation ||
            errorMessage.equals("FEDERATED_USER_ID_ALREADY_LINKED", ignoreCase = true) ||
            errorMessage.equals("EMAIL_EXISTS", ignoreCase = true)

        if (credentialConflict || !pending.isNullOrBlank()) {
            return SignInWithIdpResult.NeedsConfirmation(
                pendingToken = pending,
                oauthIdToken = oauthIdToken ?: idToken,
                oauthAccessToken = oauthAccessToken ?: accessToken,
                email = email,
                verifiedProviders = verifiedProviders,
                errorMessage = errorMessage,
            )
        }

        error("Firebase Auth signInWithIdp returned an unexpected response: ${responseText.take(300)}")
    }

    public suspend fun sendPasswordResetEmail(email: String) {
        httpClient.post(identityUrl("accounts:sendOobCode")) {
            contentType(ContentType.Application.Json)
            setBody(OobCodeRequest(requestType = "PASSWORD_RESET", email = email))
        }
    }

    public suspend fun refresh(refreshToken: String): FirebaseSession {
        val response: RefreshTokenResponse = httpClient.submitForm(
            url = "https://securetoken.googleapis.com/v1/token?key=$apiKey",
            formParameters = parametersOf(
                "grant_type" to listOf("refresh_token"),
                "refresh_token" to listOf(refreshToken),
            ),
        ).body()
        return FirebaseSession(
            localId = response.userId,
            email = null,
            idToken = response.idToken,
            refreshToken = response.refreshToken,
            expiresAtEpochMs = currentTimeMillis() + response.expiresIn.toLong() * 1000L,
            isAnonymous = false,
        )
    }

    private fun identityUrl(method: String): String =
        "https://identitytoolkit.googleapis.com/v1/$method?key=$apiKey"

    private suspend inline fun <reified T> postAuthSession(
        method: String,
        body: T,
        isAnonymous: Boolean,
    ): FirebaseSession {
        val requestJson = authRequestJson.encodeToString(serializer(), body)
        val responseText = httpClient.post(identityUrl(method)) {
            setBody(TextContent(requestJson, ContentType.Application.Json))
        }.bodyAsText()
        return parseAuthSessionResponse(responseText, isAnonymous)
    }

    private fun parseAuthSessionResponse(responseText: String, isAnonymous: Boolean): FirebaseSession {
        val root = json.parseToJsonElement(responseText).jsonObject
        parseIdentityToolkitError(root)?.let { code ->
            error("Firebase Auth error: $code")
        }
        val idToken = root.string("idToken").orEmpty()
        val refreshToken = root.string("refreshToken").orEmpty()
        val localId = root.string("localId").orEmpty()
        if (idToken.isBlank() || refreshToken.isBlank() || localId.isBlank()) {
            error("Firebase Auth error: INCOMPLETE_SESSION")
        }
        val expiresIn = root.string("expiresIn")?.toLongOrNull()
            ?: root["expiresIn"]?.jsonPrimitive?.longOrNull
            ?: 3600L
        return FirebaseSession(
            localId = localId,
            email = root.string("email"),
            idToken = idToken,
            refreshToken = refreshToken,
            expiresAtEpochMs = currentTimeMillis() + expiresIn * 1000L,
            isAnonymous = isAnonymous && root.string("email").isNullOrBlank(),
        )
    }
}

public sealed interface SignInWithIdpResult {
    public data class Success(val session: FirebaseSession) : SignInWithIdpResult
    public data class NeedsConfirmation(
        val pendingToken: String? = null,
        val oauthIdToken: String? = null,
        val oauthAccessToken: String? = null,
        val email: String? = null,
        val verifiedProviders: List<String> = emptyList(),
        val errorMessage: String? = null,
    ) : SignInWithIdpResult
}

@Serializable
private data class AnonymousSignUpRequest(
    val returnSecureToken: Boolean = true,
)

@Serializable
private data class EmailPasswordRequest(
    val email: String,
    val password: String,
    val returnSecureToken: Boolean = true,
)

@Serializable
private data class LinkAnonymousEmailRequest(
    val idToken: String,
    val email: String,
    val password: String,
    val returnSecureToken: Boolean = true,
)

@Serializable
private data class CustomTokenRequest(
    val token: String,
    val returnSecureToken: Boolean = true,
)

@Serializable
private data class SignInWithIdpRequest(
    val requestUri: String,
    val returnIdpCredential: Boolean,
    val returnSecureToken: Boolean,
    val postBody: String? = null,
    val idToken: String? = null,
    val pendingToken: String? = null,
)

@Serializable
private data class OobCodeRequest(
    val requestType: String,
    val email: String,
)

@Serializable
private data class AuthTokenResponse(
    val idToken: String = "",
    val refreshToken: String = "",
    val expiresIn: String = "3600",
    val localId: String = "",
    val email: String? = null,
    val isNewUser: Boolean? = null,
    val error: JsonObject? = null,
)

@Serializable
private data class RefreshTokenResponse(
    @SerialName("id_token") val idToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: String,
    @SerialName("user_id") val userId: String,
)

private fun AuthTokenResponse.toSession(isAnonymous: Boolean): FirebaseSession =
    FirebaseSession(
        localId = localId,
        email = email,
        idToken = idToken,
        refreshToken = refreshToken,
        expiresAtEpochMs = currentTimeMillis() + (expiresIn.toLongOrNull() ?: 3600L) * 1000L,
        isAnonymous = isAnonymous && email.isNullOrBlank(),
    )

private fun parseIdentityToolkitError(root: JsonObject): String? =
    when (val error = root["error"]) {
        is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
        is JsonPrimitive -> {
            val code = error.contentOrNull ?: return null
            val description = root["error_description"]?.jsonPrimitive?.contentOrNull
            if (description != null) "$code: $description" else code
        }
        else -> null
    }

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.stringList(name: String): List<String> {
    val el = this[name] ?: return emptyList()
    return when (el) {
        is JsonArray -> el.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        else -> el.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
    }
}

private fun String.encodeURLParameter(): String = buildString {
    val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
    for (ch in this@encodeURLParameter) {
        if (ch in allowed) append(ch)
        else append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
    }
}
