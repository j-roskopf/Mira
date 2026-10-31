package com.joetr.mira.data.auth

import kotlinx.coroutines.CompletableDeferred

public object OAuthCallbackGate {
    private var pending: CompletableDeferred<String>? = null

    public suspend fun awaitCallback(): String {
        val deferred = CompletableDeferred<String>()
        pending = deferred
        return deferred.await()
    }

    public fun complete(url: String) {
        pending?.complete(url)
        pending = null
    }
}
