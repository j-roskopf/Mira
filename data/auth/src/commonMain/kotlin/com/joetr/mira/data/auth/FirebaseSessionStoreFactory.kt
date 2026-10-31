package com.joetr.mira.data.auth

import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.network.FirebaseSessionStore

public expect fun createFirebaseSessionStore(database: MiraDatabase): FirebaseSessionStore
