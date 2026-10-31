package com.joetr.mira.updates

import android.content.Intent
import androidx.core.content.FileProvider
import com.joetr.mira.platform.AndroidContextHolder
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class AndroidAppUpdateService(
    scope: CoroutineScope,
    httpClient: HttpClient,
) : BaseAppUpdateService(scope, httpClient, UpdatePlatform.Android) {

    override suspend fun performInstall(
        update: AvailableUpdate,
        onProgress: (message: String, progress: Float?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val asset = update.asset
        if (asset == null) {
            openReleasePage(update.releasePageUrl)
            mutableState.value = AppUpdateState.Available(update)
            return@withContext
        }

        val context = AndroidContextHolder.application
            ?: error("Android application context is not available.")
        val target = File(context.cacheDir, "updates").apply { mkdirs() }
            .resolve(asset.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_"))
        val totalBytes = asset.sizeBytes.takeIf { it > 0L }
        var downloadedBytes = 0L

        onProgress("Downloading Mira update...", downloadProgress(downloadedBytes, totalBytes))

        httpClient.get(asset.downloadUrl).bodyAsChannel().toInputStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DownloadBufferSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    onProgress("Downloading Mira update...", downloadProgress(downloadedBytes, totalBytes))
                }
            }
        }

        onProgress("Ready to install Mira ${update.versionName}.", 1f)
        val confirmed = requestInstallConfirmation(update)
        if (!confirmed) {
            mutableState.value = AppUpdateState.Available(update)
            return@withContext
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
        mutableState.value = AppUpdateState.Idle
    }

    private fun downloadProgress(downloadedBytes: Long, totalBytes: Long?): Float? =
        totalBytes
            ?.takeIf { it > 0L }
            ?.let { total -> (downloadedBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) }

    private fun openReleasePage(url: String) {
        val context = AndroidContextHolder.application ?: return
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

public actual fun currentUpdatePlatform(): UpdatePlatform = UpdatePlatform.Android

public actual fun createAppUpdateService(
    scope: CoroutineScope,
    httpClient: HttpClient,
): AppUpdateService = AndroidAppUpdateService(scope, httpClient)

private const val DownloadBufferSize = 256 * 1024
