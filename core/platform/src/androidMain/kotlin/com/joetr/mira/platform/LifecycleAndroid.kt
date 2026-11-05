package com.joetr.mira.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

public actual fun registerLifecycleObserver() {
    val activity = AndroidContextHolder.activity ?: return
    activity.lifecycle.addObserver(
        object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                AppLifecycleObserver.onForeground?.invoke()
            }
        },
    )
}
