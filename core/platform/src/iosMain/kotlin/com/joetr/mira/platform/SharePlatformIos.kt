package com.joetr.mira.platform

import platform.Foundation.NSUserDefaults

private const val APP_GROUP_ID = "group.com.joetr.mira"
private const val PENDING_URL_KEY = "pendingShareUrl"

public actual fun consumePlatformShareUrl(): String? {
    val defaults = NSUserDefaults(suiteName = APP_GROUP_ID) ?: return null
    val url = defaults.stringForKey(PENDING_URL_KEY) ?: return null
    defaults.removeObjectForKey(PENDING_URL_KEY)
    return url
}
