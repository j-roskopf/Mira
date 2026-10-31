package com.joetr.mira.platform

public const val APP_NAME: String = "Mira"

public fun localDatabaseFileName(): String = "mira.db"

public fun localDatabaseRevisionKey(): String = "mira.db.revision"

public fun desktopDataDirectoryName(): String = ".mira"

public fun localStorageDirectoryName(): String = "mira"

/** True when [url] points at Firebase Storage, whose bucket CORS allows mira.joetr.com. */
public fun isFirebaseStorageUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return url.contains("firebasestorage.googleapis.com") || url.contains(".firebasestorage.app/")
}

/** Percent-encode a value for use in a URL query parameter. */
public fun encodeUrlParameter(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val code = byte.toInt() and 0xff
        val safe =
            code in 'a'.code..'z'.code ||
                code in 'A'.code..'Z'.code ||
                code in '0'.code..'9'.code ||
                code == '-'.code || code == '_'.code || code == '.'.code || code == '~'.code
        if (safe) {
            append(code.toChar())
        } else {
            append('%')
            append(code.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

public expect fun currentTimeMillis(): Long

public expect fun isNetworkAvailable(): Boolean

public expect fun openUrl(url: String)

/** Shares text with other apps, or copies it where the platform has no share sheet. */
public expect fun shareText(text: String)

/** Copies text to the platform clipboard. */
public expect fun copyText(text: String)

public expect fun keepScreenOn(enabled: Boolean)

public expect fun hapticSuccess()

public expect fun playTimerCompleteSound()

public expect class ImageCapture {
    public companion object {
        public val isAvailable: Boolean
    }
}

public expect suspend fun resizeImage(bytes: ByteArray, maxLongEdge: Int, quality: Int): ByteArray

public expect suspend fun readClipboardText(): String?

public expect fun isDebugBuild(): Boolean

public expect fun platformName(): String
