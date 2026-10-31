package com.joetr.mira.data.auth

internal actual fun webOAuthRedirectUriForErrorMessage(): String =
    "com.googleusercontent.apps.{ios-client-id}:/oauth2redirect (iOS Firebase CLIENT_ID)"
