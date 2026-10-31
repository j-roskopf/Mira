package com.joetr.mira.data.auth

import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.network.FirebaseSession
import com.joetr.mira.network.FirebaseSessionStore

/** Web auth persists in localStorage only — avoids blocking on the in-memory SQL worker. */
private class LocalStorageFirebaseSessionStore : FirebaseSessionStore {
    override suspend fun load(): FirebaseSession? = WebFirebaseSessionStorage.load()

    override suspend fun save(session: FirebaseSession?) {
        WebFirebaseSessionStorage.save(session)
    }
}

public actual fun createFirebaseSessionStore(database: MiraDatabase): FirebaseSessionStore =
    LocalStorageFirebaseSessionStore()
