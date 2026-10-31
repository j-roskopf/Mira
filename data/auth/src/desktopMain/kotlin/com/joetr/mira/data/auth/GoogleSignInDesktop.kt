package com.joetr.mira.data.auth

import com.joetr.mira.network.MiraFirebase
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val LOOPBACK_PORT = 3847

public actual suspend fun googleSignIn(firebase: MiraFirebase): GoogleSignInResult {
    val redirectUri = "http://127.0.0.1:$LOOPBACK_PORT/auth-callback"
    return coroutineScope {
        // Bind before opening the browser so a fast Google redirect cannot miss the port.
        val bound = CompletableDeferred<Unit>()
        val callbackDeferred = async(Dispatchers.IO) {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress("127.0.0.1", LOOPBACK_PORT))
                server.soTimeout = 120_000
                bound.complete(Unit)
                acceptOAuthCallback(server, LOOPBACK_PORT)
            }
        }
        bound.await()
        googleSignInWithAuthorizationCodeFlow(
            firebase = firebase,
            redirectUri = redirectUri,
            openUrl = { url ->
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(URI(url))
                }
            },
            awaitCallback = { callbackDeferred.await() },
        )
    }
}

private fun acceptOAuthCallback(server: ServerSocket, port: Int): String {
    // Browsers often hit /favicon.ico (or prefetch) on the same port; keep
    // accepting until we get the OAuth redirect itself.
    while (true) {
        server.accept().use { socket ->
            val request = socket.getInputStream().bufferedReader().readLine().orEmpty()
            val location = request.substringAfter("GET ").substringBefore(' ')
            val isCallback = location.startsWith("/auth-callback")
            val body = if (isCallback) {
                "<html><body><h1>Signed in to Mira</h1><p>You can close this tab.</p></body></html>"
            } else {
                ""
            }
            val bodyBytes = body.toByteArray()
            val response = buildString {
                appendLine(if (isCallback) "HTTP/1.1 200 OK" else "HTTP/1.1 404 Not Found")
                appendLine("Content-Type: text/html")
                appendLine("Content-Length: ${bodyBytes.size}")
                appendLine("Connection: close")
                appendLine()
                append(body)
            }
            socket.getOutputStream().write(response.toByteArray())
            if (isCallback) {
                return "http://127.0.0.1:$port$location"
            }
        }
    }
}
