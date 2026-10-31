package com.joetr.mira.app

import com.joetr.mira.platform.ShareIntentHolder

@Suppress("unused")
public fun setPendingShareUrl(url: String) {
    ShareIntentHolder.pendingUrl = url
}

@Suppress("unused")
public fun setPendingSharedRecipeToken(token: String) {
    ShareIntentHolder.pendingSharedToken = token
}
