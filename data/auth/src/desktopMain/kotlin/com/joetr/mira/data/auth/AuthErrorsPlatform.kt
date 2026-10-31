package com.joetr.mira.data.auth

internal actual fun webOAuthRedirectUriForErrorMessage(): String =
    "http://127.0.0.1:3847/auth-callback (desktop) or http://localhost:8080/auth-callback (web dev)"
