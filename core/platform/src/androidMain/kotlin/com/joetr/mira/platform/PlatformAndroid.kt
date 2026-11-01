package com.joetr.mira.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import java.io.ByteArrayOutputStream
import kotlin.math.max

public object AndroidContextHolder {
    public lateinit var application: Context
    public var activity: ComponentActivity? = null
}

public actual fun currentTimeMillis(): Long = System.currentTimeMillis()

public actual fun isNetworkAvailable(): Boolean {
    val cm = AndroidContextHolder.application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

public actual fun openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    AndroidContextHolder.application.startActivity(intent)
}

public actual fun copyText(text: String) {
    val clipboard = AndroidContextHolder.application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Mira share link", text))
}

public actual fun shareText(text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, "Share recipe")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    AndroidContextHolder.application.startActivity(chooser)
}

public actual fun keepScreenOn(enabled: Boolean) {
    val activity = AndroidContextHolder.activity ?: return
    if (enabled) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

public actual fun hapticSuccess() {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = AndroidContextHolder.application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        AndroidContextHolder.application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

public actual fun playTimerCompleteSound() = Unit

public actual class ImageCapture {
    public actual companion object {
        public actual val isAvailable: Boolean = true
    }
}

public actual suspend fun resizeImage(bytes: ByteArray, maxLongEdge: Int, quality: Int): ByteArray {
    val oriented = decodeOrientedBitmap(bytes) ?: return bytes
    val scale = maxLongEdge.toFloat() / max(oriented.width, oriented.height).coerceAtLeast(1)
    val targetW = (oriented.width * scale).toInt().coerceAtLeast(1)
    val targetH = (oriented.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
    if (scaled !== oriented) oriented.recycle()
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    scaled.recycle()
    return out.toByteArray()
}

public actual suspend fun readClipboardText(): String? {
    val clipboard = AndroidContextHolder.application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(AndroidContextHolder.application)?.toString()
}

public actual fun isDebugBuild(): Boolean {
    return try {
        Class.forName("com.joetr.mira.BuildConfig")
            .getField("DEBUG")
            .getBoolean(null)
    } catch (_: Throwable) {
        false
    }
}

public actual fun platformName(): String = "android"

public actual fun isUnmeteredNetwork(): Boolean {
    return try {
        val manager = AndroidContextHolder.application
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: SecurityException) {
        false
    }
}
