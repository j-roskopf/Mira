package com.joetr.mira.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import java.security.MessageDigest

internal class AndroidAppUpdateService(
    scope: CoroutineScope,
    httpClient: HttpClient,
) : BaseAppUpdateService(scope, httpClient, UpdatePlatform.Android) {

    override suspend fun performInstall(
        update: AvailableUpdate,
        onProgress: (message: String, progress: Float?) -> Unit,
    ): UpdateInstallResult = withContext(Dispatchers.IO) {
        val asset = update.asset ?: return@withContext openReleasePage(update)

        val context = AndroidContextHolder.application
        if (!context.packageManager.canRequestPackageInstalls()) {
            openUnknownAppSourcesSettings(context)
            return@withContext UpdateInstallResult.RequiresUserAction(
                "Allow Mira to install unknown apps, then tap Update again.",
            )
        }

        val apk = downloadApk(asset, onProgress)

        onProgress("Ready to install Mira ${update.versionName}.", 1f)
        val confirmed = requestInstallConfirmation(update)
        if (!confirmed) {
            return@withContext UpdateInstallResult.RequiresUserAction(
                "Update downloaded. Tap Update again when you're ready to install it.",
            )
        }

        onProgress("Opening Android's installer...", 1f)
        try {
            commitPackageInstallerSession(context, apk, update.versionName)
        } catch (_: Throwable) {
            openInstallIntentFallback(context, apk)
        }
        UpdateInstallResult.Started("Mira downloaded the update and opened Android's installer.")
    }

    private suspend fun downloadApk(
        asset: ReleaseAsset,
        onProgress: (message: String, progress: Float?) -> Unit,
    ): File {
        val context = AndroidContextHolder.application
        val target = File(context.cacheDir, "updates").apply { mkdirs() }
            .resolve(asset.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_"))
        val expectedSha256 = asset.sha256Digest
        val digest = if (expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null
        val totalBytes = asset.sizeBytes.takeIf { it > 0L }
        var downloadedBytes = 0L

        onProgress("Downloading Mira update...", progressFraction(downloadedBytes, totalBytes))

        httpClient.get(asset.downloadUrl).bodyAsChannel().toInputStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DownloadBufferSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest?.update(buffer, 0, read)
                    downloadedBytes += read
                    onProgress(
                        "Downloading Mira update...",
                        progressFraction(downloadedBytes, totalBytes),
                    )
                }
            }
        }

        if (expectedSha256 != null) {
            onProgress("Verifying update...", 1f)
            val actual = digest?.digest()?.joinToString("") { byte -> "%02x".format(byte) }
            if (actual != expectedSha256) {
                target.delete()
                error("Downloaded update failed SHA-256 verification.")
            }
        }
        return target
    }

    private fun commitPackageInstallerSession(
        context: Context,
        apk: File,
        versionName: String,
    ) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = packageInstaller.createSession(params)
        packageInstaller.openSession(sessionId).use { session ->
            session.openWrite("mira-$versionName.apk", 0L, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            val callback = Intent(context, AndroidUpdateInstallReceiver::class.java)
                .setAction(AndroidUpdateInstallReceiver.ActionInstallStatus)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val sender = PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender
            session.commit(sender)
        }
    }

    private fun openInstallIntentFallback(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }

    private fun openUnknownAppSourcesSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun openReleasePage(update: AvailableUpdate): UpdateInstallResult {
        val context = AndroidContextHolder.application
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.releasePageUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return UpdateInstallResult.OpenedReleasePage("Mira opened the latest release page.")
    }

    private fun progressFraction(downloadedBytes: Long, totalBytes: Long?): Float? =
        totalBytes
            ?.takeIf { it > 0L }
            ?.let { total -> (downloadedBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) }
}

/**
 * Receives the result of an Android package installer session.
 *
 * The platform may need a second confirmation from the user; forwarding that intent is what
 * actually puts the installer in front of them. A successful install relaunches Mira so the new
 * version starts immediately instead of waiting for the next manual launch.
 */
public class AndroidUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let(context::startActivity)
            }
        }
    }

    public companion object {
        public const val ActionInstallStatus: String = "com.joetr.mira.UPDATE_INSTALL_STATUS"
    }
}

public actual fun currentUpdatePlatform(): UpdatePlatform = UpdatePlatform.Android

public actual fun createAppUpdateService(
    scope: CoroutineScope,
    httpClient: HttpClient,
): AppUpdateService = AndroidAppUpdateService(scope, httpClient)

private const val DownloadBufferSize = 256 * 1024
