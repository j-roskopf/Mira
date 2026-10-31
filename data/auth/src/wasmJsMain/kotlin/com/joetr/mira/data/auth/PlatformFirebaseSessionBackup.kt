package com.joetr.mira.data.auth

import com.joetr.mira.network.FirebaseSession

internal actual suspend fun readPlatformFirebaseSession(): FirebaseSession? =
    WebFirebaseSessionStorage.load()

internal actual suspend fun writePlatformFirebaseSession(session: FirebaseSession?) {
    WebFirebaseSessionStorage.save(session)
}
