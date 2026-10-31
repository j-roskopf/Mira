package com.joetr.mira.data.auth

import com.joetr.mira.network.FirebaseSession

/** Extra platform persistence hook so web localStorage is written even if the session store lags. */
internal expect fun persistFirebaseSessionToPlatform(session: FirebaseSession)
