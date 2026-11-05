package com.joetr.mira.app

import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.joetr.mira.di.createMiraAppGraph
import com.joetr.mira.media.dispatchAndroidMediaPickerResult
import com.joetr.mira.media.dispatchAndroidNativeCameraResult
import com.joetr.mira.platform.AndroidContextHolder
import com.joetr.mira.platform.AndroidRequestCodes
import com.joetr.mira.platform.ShareIntentHolder
import com.joetr.mira.platform.dispatchAndroidActivityResult
import com.joetr.mira.platform.dispatchAndroidPermissionResult
import com.joetr.mira.platform.registerLifecycleObserver
import kotlinx.coroutines.runBlocking

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val darkWindow = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.decorView.setBackgroundColor(
            if (darkWindow) Color.parseColor("#1C1C1E") else Color.parseColor("#FFFFFF"),
        )
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        AndroidContextHolder.application = applicationContext
        AndroidContextHolder.activity = this
        registerActivityResultLaunchers()
        handleShareIntent(intent)
        val graph = runBlocking { createMiraAppGraph() }
        registerLifecycleObserver()
        setContent { App(graph) }
    }

    private fun registerActivityResultLaunchers() {
        val requestCodes = listOf(
            AndroidRequestCodes.EXPORT_FILE,
            AndroidRequestCodes.IMPORT_FILE,
            AndroidRequestCodes.PICK_MEDIA,
            AndroidRequestCodes.CAPTURE_PHOTO,
            AndroidRequestCodes.CAPTURE_VIDEO,
        )
        AndroidContextHolder.activityResultLaunchers = requestCodes.associateWith { requestCode ->
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                dispatchAndroidActivityResult(requestCode, result.resultCode, result.data)
                dispatchAndroidMediaPickerResult(requestCode, result.resultCode, result.data)
                dispatchAndroidNativeCameraResult(requestCode, result.resultCode, result.data)
            }
        }
        AndroidContextHolder.permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants -> dispatchAndroidPermissionResult(grants) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            extractSharedToken(intent.data)?.let { ShareIntentHolder.pendingSharedToken = it }
            return
        }
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = text.lines().firstOrNull { it.startsWith("http://") || it.startsWith("https://") } ?: text.trim()
        if (url.isNotBlank()) ShareIntentHolder.pendingUrl = url
    }

    private fun extractSharedToken(uri: Uri?): String? {
        if (uri?.scheme != "https" || uri.host != "mira.joetr.com") return null
        val segments = uri.pathSegments
        return segments.getOrNull(0)?.takeIf { it == "share" }
            ?.let { segments.getOrNull(1) }
            ?.takeIf { it.isNotBlank() }
    }
}
