package com.joetr.mira.data.auth

import com.joetr.mira.network.FirebaseSession

internal expect suspend fun readPlatformFirebaseSession(): FirebaseSession?

internal expect suspend fun writePlatformFirebaseSession(session: FirebaseSession?)
