package com.joetr.mira.data.auth

internal expect fun openOAuthUrl(url: String)

internal expect suspend fun awaitOAuthCallback(): String
