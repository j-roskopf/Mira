package com.joetr.mira.platform

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.browser.window

private val resizeImageWebAsyncFn: (String, Int, Int, (String) -> Unit) -> Unit =
    js(
        """
        (base64, maxLongEdge, quality, onResult) => {
            const resize = globalThis.__miraResizeImage;
            if (!resize) {
                onResult(base64);
                return;
            }
            resize(base64, maxLongEdge, quality)
                .then(onResult)
                .catch(() => onResult(base64));
        }
        """,
    )

private val epochMillis: () -> Double =
    js("() => Date.now()")

public actual fun currentTimeMillis(): Long = epochMillis().toLong()

public actual fun isNetworkAvailable(): Boolean = window.navigator.onLine

public actual fun openUrl(url: String) {
    window.open(url, "_blank")
}

private val shareTextWeb: (String) -> Unit = js(
    """
    text => {
        if (globalThis.navigator && typeof globalThis.navigator.share === 'function') {
            globalThis.navigator.share({ title: 'Mira recipe', text }).catch(() => {});
        } else if (globalThis.navigator && globalThis.navigator.clipboard) {
            globalThis.navigator.clipboard.writeText(text);
        }
    }
    """,
)

public actual fun shareText(text: String) {
    shareTextWeb(text)
}

private val copyTextWeb: (String) -> Unit = js(
    """
    text => {
        if (globalThis.navigator && globalThis.navigator.clipboard) {
            globalThis.navigator.clipboard.writeText(text).catch(() => {});
        }
    }
    """,
)

public actual fun copyText(text: String) {
    copyTextWeb(text)
}

public actual fun keepScreenOn(enabled: Boolean) = Unit

public actual fun hapticSuccess() = Unit

public actual fun playTimerCompleteSound() = Unit

public actual class ImageCapture {
    public actual companion object {
        public actual val isAvailable: Boolean = false
    }
}

@OptIn(ExperimentalEncodingApi::class)
public actual suspend fun resizeImage(bytes: ByteArray, maxLongEdge: Int, quality: Int): ByteArray {
    if (bytes.isEmpty()) return bytes
    if (!isRecognizedImage(bytes)) return bytes
    return runCatching {
        val base64 = Base64.Default.encode(bytes)
        val resultBase64 = resizeImageWeb(base64, maxLongEdge, quality)
        Base64.Default.decode(resultBase64)
    }.getOrElse { bytes }
}

private suspend fun resizeImageWeb(base64: String, maxLongEdge: Int, quality: Int): String =
    suspendCoroutine { continuation ->
        resizeImageWebAsyncFn(base64, maxLongEdge, quality, continuation::resume)
    }

private fun isRecognizedImage(bytes: ByteArray): Boolean =
    isJpegImage(bytes) ||
        isPngImage(bytes) ||
        isHeicImage(bytes) ||
        detectImageMimeType(bytes) != null

public actual suspend fun readClipboardText(): String? = null

public actual fun isDebugBuild(): Boolean = window.location.hostname == "localhost"

public actual fun platformName(): String = "web"
