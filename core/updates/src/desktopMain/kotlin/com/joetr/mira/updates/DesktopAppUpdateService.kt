package com.joetr.mira.updates

import com.joetr.mira.platform.MiraBuildInfo
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient as JavaHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import kotlin.system.exitProcess

internal class DesktopAppUpdateService(
    scope: CoroutineScope,
    httpClient: HttpClient,
) : BaseAppUpdateService(scope, httpClient, currentUpdatePlatform()) {

    private val currentVersionName: String = MiraBuildInfo.versionName
    private val githubOwner: String = MiraBuildInfo.githubOwner
    private val githubRepo: String = MiraBuildInfo.githubRepo

    override suspend fun performInstall(
        update: AvailableUpdate,
        onProgress: (message: String, progress: Float?) -> Unit,
    ): UpdateInstallResult = withContext(Dispatchers.IO) {
        val asset = update.asset ?: return@withContext openReleasePage()
        val downloaded = download(asset, onProgress)

        onProgress("Verifying update...", 1f)
        verifySha256(downloaded, asset.sha256Digest)

        onProgress("Ready to install Mira ${update.versionName}.", 1f)
        val confirmed = requestInstallConfirmation(update)
        if (!confirmed) {
            return@withContext UpdateInstallResult.RequiresUserAction(
                "Update downloaded. Install it again when you're ready to close Mira.",
            )
        }

        onProgress("Starting installer...", 1f)
        launchInstallerAndExit(downloaded)
    }

    private fun download(
        asset: ReleaseAsset,
        onProgress: (String, Float?) -> Unit,
    ): File {
        val target = File(
            File(System.getProperty("java.io.tmpdir"), "mira-updates").apply { mkdirs() },
            asset.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_"),
        )
        val request = HttpRequest.newBuilder(URI.create(asset.downloadUrl))
            .timeout(Duration.ofMinutes(10))
            .header("User-Agent", "Mira/$currentVersionName")
            .GET()
            .build()

        val response = JavaHttpClient.newBuilder()
            .followRedirects(JavaHttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() !in 200..299) {
            target.delete()
            error("Update download failed: HTTP ${response.statusCode()}")
        }

        val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(asset.sizeBytes)
            .takeIf { it > 0L }
        var downloadedBytes = 0L

        onProgress("Downloading Mira update...", progressFraction(downloadedBytes, totalBytes))

        target.outputStream().use { output ->
            response.body().use { input ->
                val buffer = ByteArray(DownloadBufferSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    onProgress("Downloading Mira update...", progressFraction(downloadedBytes, totalBytes))
                }
            }
        }
        return target
    }

    private fun verifySha256(file: File, expected: String?) {
        if (expected == null) return
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        check(actual == expected) { "Downloaded update failed SHA-256 verification." }
    }

    private fun launchInstallerAndExit(file: File): UpdateInstallResult {
        val relaunchCommand = ProcessHandle.current().info().command().orElse(null)?.takeIf { it.isNotBlank() }
        val platform = currentUpdatePlatform()

        when (platform) {
            UpdatePlatform.Windows -> {
                val helper = helperFile("mira-update.cmd")
                helper.writeText(windowsInstallerHelperScript(file.absolutePath, relaunchCommand))
                ProcessBuilder("cmd", "/c", "start", "", helper.absolutePath).start()
                exitProcess(0)
            }
            UpdatePlatform.MacOs -> {
                val helper = helperFile("mira-update.sh")
                helper.writeText(
                    if (file.extension.equals("pkg", ignoreCase = true)) {
                        macPkgInstallerHelperScript(
                            pkgPath = file.absolutePath,
                            bundleId = MacBundleId,
                            appName = MacAppName,
                        )
                    } else {
                        macDmgInstallerHelperScript(
                            dmgPath = file.absolutePath,
                            targetAppBundle = currentMacAppBundle(),
                            parentProcessId = ProcessHandle.current().pid(),
                        )
                    },
                )
                helper.setExecutable(true)
                macInstallerProcessBuilder(helper.absolutePath).start()
                exitProcess(0)
            }
            UpdatePlatform.LinuxDeb -> {
                launchLinuxInstaller(file, flatpak = false, relaunchCommand = relaunchCommand)
                exitProcess(0)
            }
            UpdatePlatform.LinuxFlatpak -> {
                launchLinuxInstaller(file, flatpak = true, relaunchCommand = "flatpak run com.joetr.mira")
                exitProcess(0)
            }
            else -> return openReleasePage()
        }
    }

    private fun openReleasePage(): UpdateInstallResult {
        val url = "https://github.com/$githubOwner/$githubRepo/releases/latest"
        val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
        if (desktop != null && desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
            desktop.browse(URI(url))
        }
        return UpdateInstallResult.OpenedReleasePage("Mira opened the latest release page.")
    }

    private fun helperFile(name: String): File =
        File(File(System.getProperty("java.io.tmpdir"), "mira-updates").apply { mkdirs() }, name)

    private fun launchLinuxInstaller(file: File, flatpak: Boolean, relaunchCommand: String?) {
        val insideFlatpak = !System.getenv("FLATPAK_ID").isNullOrBlank()
        val installFile = if (insideFlatpak && flatpak) copyFlatpakBundleToHostDownloads(file) else file.absolutePath
        val helper = helperFile("mira-update.sh")
        helper.writeText(
            linuxInstallerHelperScript(
                filePath = installFile,
                flatpak = flatpak,
                insideFlatpak = insideFlatpak,
                relaunchCommand = relaunchCommand,
            ),
        )
        helper.setExecutable(true)
        ProcessBuilder("/bin/sh", helper.absolutePath).start()
    }

    private fun copyFlatpakBundleToHostDownloads(sandboxFile: File): String {
        val hostFileName = sandboxFile.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val hostPath = resolveHostDownloadsPath(hostFileName)
        val copy = ProcessBuilder(
            "flatpak-spawn",
            "--host",
            "sh",
            "-c",
            "cat > ${hostPath.shellQuote()}",
        )
            .redirectInput(sandboxFile)
            .redirectErrorStream(true)
            .start()
        check(copy.waitFor() == 0) { "Couldn't copy the Flatpak update to the host Downloads folder." }
        return hostPath
    }

    private fun resolveHostDownloadsPath(fileName: String): String {
        val query = ProcessBuilder(
            "flatpak-spawn",
            "--host",
            "sh",
            "-c",
            "printf '%s' \"${'$'}HOME/Downloads/$fileName\"",
        )
            .redirectErrorStream(true)
            .start()
        val hostPath = query.inputStream.bufferedReader().readText().trim()
        check(query.waitFor() == 0 && hostPath.isNotBlank()) {
            "Couldn't resolve the host Downloads path for the Flatpak update."
        }
        return hostPath
    }

    private fun progressFraction(downloadedBytes: Long, totalBytes: Long?): Float? =
        totalBytes
            ?.takeIf { it > 0L }
            ?.let { total -> (downloadedBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) }

    private fun currentMacAppBundle(): String? {
        val launcherPath = ProcessHandle.current().info().command().orElse(null)
        val packagedCodePath = runCatching {
            File(DesktopAppUpdateService::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
        }.getOrNull()
        return sequenceOf(launcherPath, packagedCodePath)
            .mapNotNull(::macAppBundleForPath)
            .firstOrNull()
    }
}

private const val DownloadBufferSize = 256 * 1024

public actual fun currentUpdatePlatform(): UpdatePlatform {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "win" in os -> UpdatePlatform.Windows
        "mac" in os -> UpdatePlatform.MacOs
        System.getenv("FLATPAK_ID") == "com.joetr.mira" -> UpdatePlatform.LinuxFlatpak
        "linux" in os -> UpdatePlatform.LinuxDeb
        else -> UpdatePlatform.Other
    }
}

public actual fun createAppUpdateService(
    scope: CoroutineScope,
    httpClient: HttpClient,
): AppUpdateService = DesktopAppUpdateService(scope, httpClient)

internal fun windowsInstallerHelperScript(
    msiPath: String,
    relaunchCommand: String?,
): String {
    val relaunch = relaunchCommand?.windowsCmdLine()
    val relaunchLine = if (relaunch == null) "  rem No relaunch command found." else "  start \"\" $relaunch"
    return """
        @echo off
        timeout /t 1 /nobreak >NUL
        msiexec /i ${msiPath.windowsCmdLine()} /passive /norestart
        if %errorlevel%==0 (
        $relaunchLine
        )
    """.trimIndent()
}

internal fun macDmgInstallerHelperScript(
    dmgPath: String,
    targetAppBundle: String?,
    parentProcessId: Long,
): String {
    if (targetAppBundle == null) return macDmgOpenHelperScript(dmgPath)

    return """
        #!/bin/sh
        set -eu

        dmg_path=${dmgPath.shellQuote()}
        target_app=${targetAppBundle.shellQuote()}
        parent_pid=$parentProcessId
        work_dir="${'$'}(/usr/bin/mktemp -d "${'$'}{TMPDIR:-/tmp}/mira-update.XXXXXX")"
        mount_point="${'$'}work_dir/mount"
        staging_app="${'$'}{target_app}.update"
        backup_app="${'$'}{target_app}.previous"
        should_reopen=1

        shell_quote() {
          printf "'%s'" "${'$'}(printf '%s' "${'$'}1" | /usr/bin/sed "s/'/'\\\"'\\\"'/g")"
        }

        reopen_current_app() {
          [ ! -d "${'$'}target_app" ] || /usr/bin/open "${'$'}target_app"
        }

        cleanup() {
          /usr/bin/hdiutil detach "${'$'}mount_point" -quiet >/dev/null 2>&1 || true
          /bin/rm -rf "${'$'}work_dir"
          if [ "${'$'}should_reopen" -eq 1 ]; then
            reopen_current_app
          fi
        }
        trap 'exit 1' HUP INT TERM
        trap cleanup EXIT

        while /bin/kill -0 "${'$'}parent_pid" 2>/dev/null; do
          /bin/sleep 0.1
        done

        /bin/mkdir -p "${'$'}mount_point"
        /usr/bin/hdiutil attach "${'$'}dmg_path" -nobrowse -readonly -mountpoint "${'$'}mount_point" >/dev/null
        source_app=""
        for candidate in "${'$'}mount_point"/*.app; do
          if [ -d "${'$'}candidate" ]; then
            source_app="${'$'}candidate"
            break
          fi
        done
        if [ -z "${'$'}source_app" ]; then
          echo "The update disk image does not contain an app bundle." >&2
          exit 1
        fi
        /usr/bin/codesign --verify --deep --strict "${'$'}source_app"

        install_app() {
          /bin/rm -rf "${'$'}staging_app" "${'$'}backup_app"
          /usr/bin/ditto "${'$'}source_app" "${'$'}staging_app"
          if [ -e "${'$'}target_app" ]; then
            /bin/mv "${'$'}target_app" "${'$'}backup_app"
          fi
          if /bin/mv "${'$'}staging_app" "${'$'}target_app"; then
            /bin/rm -rf "${'$'}backup_app"
          else
            [ ! -e "${'$'}backup_app" ] || /bin/mv "${'$'}backup_app" "${'$'}target_app"
            return 1
          fi
        }

        if [ -w "${'$'}(/usr/bin/dirname "${'$'}target_app")" ]; then
          if ! install_app; then
            exit 1
          fi
        else
          admin_command="/bin/rm -rf ${'$'}(shell_quote "${'$'}staging_app") ${'$'}(shell_quote "${'$'}backup_app"); /usr/bin/ditto ${'$'}(shell_quote "${'$'}source_app") ${'$'}(shell_quote "${'$'}staging_app"); if [ -e ${'$'}(shell_quote "${'$'}target_app") ]; then /bin/mv ${'$'}(shell_quote "${'$'}target_app") ${'$'}(shell_quote "${'$'}backup_app"); fi; if /bin/mv ${'$'}(shell_quote "${'$'}staging_app") ${'$'}(shell_quote "${'$'}target_app"); then /bin/rm -rf ${'$'}(shell_quote "${'$'}backup_app"); else if [ -e ${'$'}(shell_quote "${'$'}backup_app") ]; then /bin/mv ${'$'}(shell_quote "${'$'}backup_app") ${'$'}(shell_quote "${'$'}target_app"); fi; exit 1; fi"
          escaped_command="${'$'}(printf '%s' "${'$'}admin_command" | /usr/bin/sed 's/\\\\/\\\\\\\\/g; s/\"/\\\\\"/g')"
          if ! /usr/bin/osascript -e "do shell script \"${'$'}escaped_command\" with administrator privileges"; then
            exit 1
          fi
        fi

        should_reopen=0
        /usr/bin/open "${'$'}target_app"
    """.trimIndent() + "\n"
}

internal fun macDmgOpenHelperScript(dmgPath: String): String =
    """
        #!/bin/sh
        sleep 1
        exec /usr/bin/open -W ${dmgPath.shellQuote()}
    """.trimIndent() + "\n"

internal fun macPkgInstallerHelperScript(
    pkgPath: String,
    bundleId: String = MacBundleId,
    appName: String = MacAppName,
): String =
    """
        #!/bin/sh
        sleep 1
        /usr/bin/open -W ${pkgPath.shellQuote()}
        /usr/bin/open -b ${bundleId.shellQuote()} || /usr/bin/open -a ${appName.shellQuote()}
    """.trimIndent() + "\n"

internal const val MacBundleId: String = "com.joetr.mira"
internal const val MacAppName: String = "Mira"

internal fun macAppBundleForPath(path: String?): String? {
    if (path == null) return null
    val markerIndex = sequenceOf(".app/", ".app\\")
        .mapNotNull { marker -> path.indexOf(marker).takeIf { it >= 0 } }
        .minOrNull() ?: return null
    return path.substring(0, markerIndex + ".app".length)
        .takeIf { File(it).isDirectory }
}

internal fun macInstallerProcessBuilder(helperPath: String): ProcessBuilder =
    ProcessBuilder("/bin/sh", helperPath)
        .redirectInput(File("/dev/null"))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)

internal fun linuxInstallerHelperScript(
    filePath: String,
    flatpak: Boolean,
    insideFlatpak: Boolean,
    relaunchCommand: String?,
): String {
    val installCommand = if (flatpak) {
        "flatpak install --user -y ${filePath.shellQuote()} || flatpak install -y ${filePath.shellQuote()}"
    } else {
        "pkexec sh -c ${(("dpkg -i " + filePath.shellQuote()) + " || apt-get install -f -y").shellQuote()}"
    }
    val relaunchBackground = relaunchCommand?.let { command ->
        "(sh -c ${command.shellQuote()} >/dev/null 2>&1 &)"
    } ?: "(sh -c 'mira' >/dev/null 2>&1 &)"
    val hostPrefix = if (insideFlatpak) "flatpak-spawn --host " else ""
    return """
        #!/bin/sh
        sleep 1
        ${hostPrefix}sh -c ${(installCommand + " && " + relaunchBackground).shellQuote()}
    """.trimIndent() + "\n"
}

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun String.windowsCmdLine(): String =
    "\"" + replace("\"", "\\\"") + "\""
