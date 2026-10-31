package com.joetr.mira.media

import java.io.File
import java.util.Locale

internal enum class DesktopOs { MAC, WINDOWS, LINUX }

internal val desktopOs: DesktopOs by lazy {
    val name = System.getProperty("os.name").orEmpty().lowercase(Locale.US)
    when {
        name.contains("mac") || name.contains("darwin") -> DesktopOs.MAC
        name.contains("win") -> DesktopOs.WINDOWS
        else -> DesktopOs.LINUX
    }
}

/**
 * Locates the FFmpeg binary that `jpackage` bundled next to the app.
 *
 * Capture on desktop is an FFmpeg subprocess rather than a JVM library: there is no camera API in
 * the JDK, JavaCV's OpenCV dependency adds hundreds of megabytes per platform, and a size-trimmed
 * static FFmpeg lands around 10–20 MB while also giving poster frames, waveform peaks and local
 * transcoding for free.
 *
 * Lookup order: the packaged app resources, then a dev-build location, then `PATH` so the app still
 * works when run from Gradle before anything has been packaged.
 */
internal object FfmpegBinary {

    private val executableName: String
        get() = if (desktopOs == DesktopOs.WINDOWS) "ffmpeg.exe" else "ffmpeg"

    val path: String? by lazy { locate() }

    val isAvailable: Boolean get() = path != null

    private fun locate(): String? {
        // jpackage sets this to the app's resource directory.
        System.getProperty("compose.application.resources.dir")?.let { dir ->
            val candidate = File(dir, executableName)
            if (candidate.canExecute()) return candidate.absolutePath
        }

        // Running from Gradle: scripts/build-ffmpeg.sh drops the binary here.
        val devCandidate = File("build/ffmpeg/$executableName")
        if (devCandidate.canExecute()) return devCandidate.absolutePath

        return onPath()
    }

    private fun onPath(): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        val separator = if (desktopOs == DesktopOs.WINDOWS) ";" else ":"
        return pathEnv.split(separator)
            .asSequence()
            .map { File(it, executableName) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }
}

/**
 * The platform's capture input, as FFmpeg names it.
 *
 * macOS: `avfoundation` takes `video:audio` device indices in one `-i`.
 * Windows: `dshow` takes named devices.
 * Linux: `v4l2` for video and `pulse` for audio, as two separate inputs.
 */
internal fun audioInputArgs(): List<String> = when (desktopOs) {
    DesktopOs.MAC -> listOf("-f", "avfoundation", "-i", ":0")
    DesktopOs.WINDOWS -> listOf("-f", "dshow", "-i", "audio=${defaultWindowsAudioDevice()}")
    DesktopOs.LINUX -> listOf("-f", "pulse", "-i", "default")
}

/**
 * Pixel formats to try for the macOS camera, best first.
 *
 * avfoundation requests **yuv420p** when nothing is specified, and most Mac cameras do not offer
 * it — they report `uyvy422`, `yuyv422`, `nv12`, `0rgb`, `bgr0`. Leaving it unset therefore fails
 * on the built-in camera of a MacBook Pro, so one has to be named. `nv12` is the native format for
 * Apple camera hardware; the rest are fallbacks for whatever a given device actually reports.
 */
internal val MAC_PIXEL_FORMATS: List<String> =
    listOf("nv12", "uyvy422", "yuyv422", "0rgb", "bgr0")

/** The device list FFmpeg prints when it rejects a pixel format, so a retry can pick a real one. */
internal fun parseSupportedPixelFormats(stderr: String): List<String> {
    if (!stderr.contains(PIXEL_FORMAT_ERROR)) return emptyList()
    return stderr.lineSequence()
        .dropWhile { !it.contains("Supported pixel formats:") }
        .drop(1)
        .map { line -> line.substringAfterLast(']').trim() }
        .takeWhile { it.isNotEmpty() && !it.contains(' ') }
        .toList()
}

internal const val PIXEL_FORMAT_ERROR: String = "not supported by the input device"

internal fun videoInputArgs(pixelFormat: String? = null): List<String> = when (desktopOs) {
    DesktopOs.MAC -> listOf(
        "-f", "avfoundation",
        // Required: the default of 29.97 is rejected by the built-in camera.
        "-framerate", "30",
        "-pixel_format", pixelFormat ?: MAC_PIXEL_FORMATS.first(),
        "-i", "0:0",
    )
    DesktopOs.WINDOWS -> listOf(
        "-f", "dshow",
        "-i", "video=${defaultWindowsVideoDevice()}:audio=${defaultWindowsAudioDevice()}",
    )
    DesktopOs.LINUX -> listOf(
        "-f", "v4l2", "-framerate", "30", "-i", "/dev/video0",
        "-f", "pulse", "-i", "default",
    )
}

/**
 * DirectShow needs device names rather than indices, and they differ per machine.
 *
 * These defaults cover the common case; Settings lets the name be overridden, and
 * `ffmpeg -list_devices true -f dshow -i dummy` is what populates that picker.
 */
private fun defaultWindowsVideoDevice(): String =
    System.getProperty("mira.capture.videoDevice") ?: "Integrated Camera"

private fun defaultWindowsAudioDevice(): String =
    System.getProperty("mira.capture.audioDevice") ?: "Microphone"
