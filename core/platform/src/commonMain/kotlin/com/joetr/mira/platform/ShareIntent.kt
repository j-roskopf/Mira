package com.joetr.mira.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

public object ShareIntentHolder {
    public var pendingUrl: String? = null

    private val pendingSharedTokenState = MutableStateFlow<String?>(null)
    public val pendingSharedTokenFlow = pendingSharedTokenState.asStateFlow()

    public var pendingSharedToken: String?
        get() = pendingSharedTokenState.value
        set(value) {
            pendingSharedTokenState.value = value
        }
}
