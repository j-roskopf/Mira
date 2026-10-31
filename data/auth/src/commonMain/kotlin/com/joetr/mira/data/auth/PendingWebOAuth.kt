package com.joetr.mira.data.auth

import com.joetr.mira.network.MiraFirebase

/**
 * Completes a Google OAuth redirect callback when the web app loads at `/auth-callback`.
 * No-op on platforms that do not use a browser redirect (Android, desktop loopback, etc.).
 */
public expect suspend fun resumeWebOAuthSignIn(firebase: MiraFirebase): GoogleSignInResult?
