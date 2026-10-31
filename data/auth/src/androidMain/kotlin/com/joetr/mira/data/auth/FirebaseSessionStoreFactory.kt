package com.joetr.mira.data.auth

import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.network.FirebaseSessionStore

public actual fun createFirebaseSessionStore(database: MiraDatabase): FirebaseSessionStore =
    DatabaseFirebaseSessionStore(database)
