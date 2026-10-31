package com.joetr.mira.network

private val NETWORK_ERROR_CLASS_NAMES = setOf(
    "UnknownHostException",
    "ConnectException",
    "SocketTimeoutException",
    "ConnectTimeoutException",
    "HttpRequestTimeoutException",
    "UnresolvedAddressException",
    "NoRouteToHostException",
)

private val NETWORK_ERROR_PHRASES = listOf(
    "Unable to resolve host",
    "No address associated with hostname",
    "Network is unreachable",
    "failed to connect",
    "Connection refused",
    "Connection timed out",
    "timed out",
)

/** True when [throwable] (or its causes) indicate the device cannot reach the network right now. */
public fun Throwable.isNetworkConnectivityError(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val className = current::class.simpleName.orEmpty()
        if (className in NETWORK_ERROR_CLASS_NAMES) return true
        val message = current.message.orEmpty()
        if (NETWORK_ERROR_PHRASES.any { phrase -> message.contains(phrase, ignoreCase = true) }) {
            return true
        }
        current = current.cause
    }
    return false
}
