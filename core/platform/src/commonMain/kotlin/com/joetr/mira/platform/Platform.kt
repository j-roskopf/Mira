package com.joetr.mira.platform

public const val APP_NAME: String = "Mira"

public fun localDatabaseFileName(): String = "mira.db"

public fun localDatabaseRevisionKey(): String = "mira.db.revision"

public fun desktopDataDirectoryName(): String = ".mira"

public fun localStorageDirectoryName(): String = "mira"

/**
 * Coil on web fetches image bytes through Ktor, so cross-origin hosts must opt into CORS.
 *
 * Firebase Storage download URLs are loaded directly (bucket CORS allows mira.joetr.com).
 * Other remote hosts still go through the proxyImage Cloud Function.
 */
public fun imageUrlForDisplay(imageUrl: String?): String? =
    imageUrl?.let(::remoteImageUrlForPlatform)

/** True when [imageUrl] is already hosted in Firebase Storage (CORS-enabled for web). */
public fun isFirebaseStorageImageUrl(imageUrl: String?): Boolean {
    if (imageUrl.isNullOrBlank()) return false
    return imageUrl.contains("firebasestorage.googleapis.com") ||
        imageUrl.contains(".firebasestorage.app/")
}

/** Same routing as [imageUrlForDisplay], for downloading remote images on web. */
public fun remoteImageUrlForPlatform(imageUrl: String): String {
    if (platformName() != "web") return imageUrl
    if (!imageUrl.startsWith("http://") && !imageUrl.startsWith("https://")) return imageUrl
    // Hosted recipe images already allow CORS — proxying them doubles latency and
    // pays Cloud Function cold starts on every grid/detail load.
    if (isFirebaseStorageImageUrl(imageUrl)) return imageUrl
    val projectId = MiraConfig.FIREBASE_PROJECT_ID
    if (projectId.isBlank()) return imageUrl
    val region = MiraConfig.FIREBASE_FUNCTIONS_REGION
    return "https://$region-$projectId.cloudfunctions.net/proxyImage?url=${encodeUrlParameter(imageUrl)}"
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

/** Target product search for [query]. */
public fun targetSearchUrl(query: String): String =
    "https://www.target.com/s?searchTerm=${encodeUrlParameter(query)}"

/** Hy-Vee Aisles Online search for [query]. */
public fun hyVeeSearchUrl(query: String): String =
    "https://www.hy-vee.com/aisles-online/search?search=${encodeUrlParameter(query)}"

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
