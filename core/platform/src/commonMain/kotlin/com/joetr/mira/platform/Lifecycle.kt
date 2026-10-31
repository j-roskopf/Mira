package com.joetr.mira.platform

public object AppLifecycleObserver {
    public var onForeground: (() -> Unit)? = null
}

public expect fun registerLifecycleObserver()
