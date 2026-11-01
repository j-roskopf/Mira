package com.joetr.mira.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ensureActive

public actual fun createMediaPlayerController(): MediaPlayerController =
    DesktopMediaPlayerController()

/**
 * Desktop playback, decoded by the same bundled FFmpeg that handles capture.
 *
 * The JDK can play uncompressed PCM through `javax.sound.sampled` but has no AAC or H.264 decoder,
 * so FFmpeg decodes to raw PCM for audio and to MJPEG frames for video. Since the binary is already
 * bundled for capture, playback costs nothing extra — which is the compounding argument for the
 * subprocess approach over a media library.
 *
 * Audio and video run as two processes over the same file, paced against the wall clock. A file is
 * not a camera, so opening it twice is fine.
 */
internal class DesktopMediaPlayerController : MediaPlayerController {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    internal val frame = MutableStateFlow<ImageBitmap?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var sourcePath: String? = null
    private var tempFile: File? = null
    private var kind: CaptureKind = CaptureKind.AUDIO

    private val audioLine = AtomicReference<SourceDataLine?>(null)
    private var audioProcess: Process? = null
    private var videoProcess: Process? = null
    private var audioJob: Job? = null
    private var videoJob: Job? = null
    private var clockJob: Job? = null

    private var startedAtMs: Long = 0
    private var startOffsetMs: Long = 0

    override suspend fun load(source: MediaSource, kind: CaptureKind) {
        release()
        this.kind = kind
        _state.value = PlaybackState(preparing = true)

        if (!FfmpegBinary.isAvailable) {
            _state.value = PlaybackState(errorMessage = "FFmpeg is not available in this build")
            return
        }

        val path = withContext(Dispatchers.IO) { resolvePath(source) }
        if (path == null) {
            _state.value = PlaybackState(errorMessage = "Could not open this recording")
            return
        }
        sourcePath = path
        _state.value = PlaybackState(durationMs = probeDurationMs(path))
    }

    override suspend fun play() {
        val path = sourcePath ?: return
        if (_state.value.playing) return
        val from = if (_state.value.ended) 0L else _state.value.positionMs

        startOffsetMs = from
        startedAtMs = System.currentTimeMillis()
        _state.value = _state.value.copy(playing = true, ended = false, preparing = false)

        audioJob = scope.launch { streamAudio(path, from) }
        if (kind == CaptureKind.VIDEO) {
            videoJob = scope.launch { streamVideo(path, from) }
        }
        clockJob = scope.launch { trackPosition() }
    }

    override fun pause() {
        stopProcesses()
        _state.value = _state.value.copy(playing = false)
    }

    override suspend fun seekTo(positionMs: Long) {
        val wasPlaying = _state.value.playing
        stopProcesses()
        _state.value = _state.value.copy(positionMs = positionMs, ended = false, playing = false)
        if (wasPlaying) play()
    }

    override fun release() {
        stopProcesses()
        tempFile?.delete()
        tempFile = null
        sourcePath = null
        frame.value = null
        _state.value = PlaybackState()
    }

    private fun stopProcesses() {
        audioJob?.cancel()
        videoJob?.cancel()
        clockJob?.cancel()
        audioProcess?.destroyForcibly()
        videoProcess?.destroyForcibly()
        audioProcess = null
        videoProcess = null
        audioLine.getAndSet(null)?.let { line ->
            runCatching { line.stop(); line.flush(); line.close() }
        }
    }

    /**
     * Decodes to signed 16-bit stereo PCM and writes it straight into the output line.
     *
     * `SourceDataLine.write` blocks while the buffer drains, which is what paces playback — no
     * separate timing loop is needed for the audio itself.
     */
    private suspend fun streamAudio(path: String, fromMs: Long) = withContext(Dispatchers.IO) {
        val binary = FfmpegBinary.path ?: return@withContext
        val format = AudioFormat(PLAYBACK_SAMPLE_RATE.toFloat(), 16, PLAYBACK_CHANNELS, true, false)
        val line = runCatching {
            (AudioSystem.getLine(javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine)
                .apply {
                    open(format, 64 * 1024)
                }
        }.getOrNull() ?: return@withContext

        val process = runCatching {
            ProcessBuilder(buildAudioPlaybackCommand(binary, path, fromMs))
                .redirectErrorStream(false)
                .start()
        }.getOrNull()

        if (process == null) {
            line.close()
            return@withContext
        }
        audioProcess = process

        val diagnostics = scope.launch { process.errorStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) } }
        try {
            ensureActive()
            audioLine.set(line)
            var primed = false
            copyPcmFrames(process.inputStream, format.frameSize, { isActive }) { buffer, offset, count ->
                val written = line.write(buffer, offset, count)
                // Queue audio before starting the device to avoid an empty-buffer underrun.
                if (!primed && written > 0) {
                    line.start()
                    primed = true
                }
                written
            }
            if (isActive) {
                line.drain()
                _state.value = _state.value.copy(playing = false, ended = true)
            }
        } catch (failure: Exception) {
            if (isActive) {
                _state.value = _state.value.copy(playing = false, errorMessage = "Could not play this recording's audio")
            }
        } finally {
            audioLine.compareAndSet(line, null)
            diagnostics.cancel()
            runCatching {
                line.stop()
                line.close()
            }
            process.destroyForcibly()
        }
    }

    /** Decodes video to MJPEG frames, paced against the wall clock so it tracks the audio. */
    private suspend fun streamVideo(path: String, fromMs: Long) = withContext(Dispatchers.IO) {
        val binary = FfmpegBinary.path ?: return@withContext
        val fps = 24
        val process = runCatching {
            ProcessBuilder(buildVideoPlaybackCommand(binary, path, fromMs, fps))
                .redirectErrorStream(false)
                .start()
        }.getOrNull() ?: return@withContext
        videoProcess = process

        var frameIndex = 0
        readJpegFrames(process.inputStream) { bytes ->
            if (!isActive) return@readJpegFrames false
            // Hold each frame until its presentation time, so video does not race ahead of audio.
            val targetMs = startedAtMs + (frameIndex * 1000L / fps)
            val waitMs = targetMs - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            decodeJpegFrame(bytes)?.let { frame.value = it }
            frameIndex++
            true
        }
        process.destroyForcibly()
    }

    private suspend fun trackPosition() {
        while (scope.isActive && _state.value.playing) {
            val elapsed = System.currentTimeMillis() - startedAtMs + startOffsetMs
            val duration = _state.value.durationMs
            _state.value = _state.value.copy(
                positionMs = if (duration > 0) elapsed.coerceAtMost(duration) else elapsed,
            )
            delay(100)
        }
    }

    /** Reads `Duration: HH:MM:SS.ss` from FFmpeg's own report of the file. */
    private suspend fun probeDurationMs(path: String): Long = withContext(Dispatchers.IO) {
        val binary = FfmpegBinary.path ?: return@withContext 0L
        val process = runCatching {
            ProcessBuilder(buildProbeCommand(binary, path))
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return@withContext 0L

        val text = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
        process.waitFor(5, TimeUnit.SECONDS)

        val match = Regex("""Duration:\s*(\d+):(\d{2}):(\d{2})\.(\d{1,2})""").find(text)
            ?: return@withContext 0L
        val (hours, minutes, seconds, fraction) = match.destructured
        hours.toLong() * 3_600_000 +
            minutes.toLong() * 60_000 +
            seconds.toLong() * 1_000 +
            fraction.padEnd(2, '0').toLong() * 10
    }

    private fun resolvePath(source: MediaSource): String? = when (source) {
        is MediaSource.LocalFile -> source.path.takeIf { File(it).exists() }
        is MediaSource.Remote -> source.url
        is MediaSource.Bytes -> {
            val extension = if (source.mime.startsWith("video/")) "mp4" else "m4a"
            runCatching {
                File.createTempFile("mira-playback-", ".$extension").apply {
                    deleteOnExit()
                    writeBytes(source.bytes)
                    tempFile = this
                }.absolutePath
            }.getOrNull()
        }
    }
}

/**
 * Splits an MJPEG stream into frames on its SOI/EOI markers.
 *
 * MJPEG over a pipe is concatenated JPEGs with no framing of its own, so boundaries have to be
 * found by scanning for `FFD8` … `FFD9`.
 */
internal suspend fun readJpegFrames(stream: InputStream, onFrame: suspend (ByteArray) -> Boolean) {
    var buffer = ByteArray(256 * 1024)
    var size = 0
    var scanned = 0
    val chunk = ByteArray(32 * 1024)

    try {
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break

            if (size + read > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, size + read))
            }
            chunk.copyInto(buffer, size, 0, read)
            size += read

            while (true) {
                val start = indexOfMarker(buffer, size, 0xD8, scanned)
                if (start < 0) {
                    // Keep one byte back: a marker may straddle the next read.
                    scanned = maxOf(0, size - 1)
                    break
                }
                val end = indexOfMarker(buffer, size, 0xD9, start + 2)
                if (end < 0) {
                    // Resume from this frame's own start. Skipping ahead to the end of the buffer
                    // loses the SOI we just found, and the next pass then latches onto a *later*
                    // frame — which silently drops every frame that straddles a read.
                    scanned = start
                    break
                }

                if (!onFrame(buffer.copyOfRange(start, end + 2))) return

                // One compaction per frame rather than one shift per byte: the previous version
                // removed the frame a byte at a time, which cost roughly half of real time on a
                // 30fps preview and starved the frame counter the stall watchdog reads.
                val consumed = end + 2
                buffer.copyInto(buffer, 0, consumed, size)
                size -= consumed
                scanned = 0
            }
        }
    } catch (_: Throwable) {
        // The stream closes when capture or playback stops.
    }
}

private fun indexOfMarker(buffer: ByteArray, size: Int, marker: Int, from: Int): Int {
    var index = maxOf(0, from)
    val limit = size - 1
    val second = marker.toByte()
    while (index < limit) {
        if (buffer[index] == 0xFF.toByte() && buffer[index + 1] == second) return index
        index++
    }
    return -1
}

internal fun decodeJpegFrame(bytes: ByteArray): ImageBitmap? =
    runCatching { decodeJpegFrameOrThrow(bytes) }.getOrNull()

/** Same decode without the catch, so failures can be diagnosed instead of silently counted as zero. */
internal fun decodeJpegFrameOrThrow(bytes: ByteArray): ImageBitmap =
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()

@Composable
public actual fun VideoSurface(
    controller: MediaPlayerController,
    modifier: Modifier,
) {
    val desktopController = controller as? DesktopMediaPlayerController
    val frame by (desktopController?.frame ?: MutableStateFlow(null)).collectAsState()

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        frame?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * Decodes an attachment to raw PCM for `javax.sound.sampled`.
 *
 * Extracted alongside the capture command so the trimmed FFmpeg build is verified to carry what
 * playback needs — the pcm_s16le encoder and the s16le muxer, neither of which a capture-only
 * audit would have caught.
 */
internal fun buildAudioPlaybackCommand(
    binary: String,
    path: String,
    fromMs: Long,
): List<String> = listOf(
    binary, "-hide_banner", "-loglevel", "error",
    "-ss", (fromMs / 1000.0).toString(),
    "-i", path,
    "-vn",
    "-f", "s16le", "-acodec", "pcm_s16le",
    "-ar", PLAYBACK_SAMPLE_RATE.toString(), "-ac", PLAYBACK_CHANNELS.toString(),
    "pipe:1",
)

/** Decodes video to MJPEG frames for the Compose surface. */
internal fun buildVideoPlaybackCommand(
    binary: String,
    path: String,
    fromMs: Long,
    fps: Int,
): List<String> = listOf(
    binary, "-hide_banner", "-loglevel", "error",
    "-ss", (fromMs / 1000.0).toString(),
    "-i", path,
    "-an",
    "-r", fps.toString(),
    "-f", "mjpeg", "-q:v", "6",
    "pipe:1",
)

/** Asks FFmpeg to report the file, so its own duration line can be read back. */
internal fun buildProbeCommand(binary: String, path: String): List<String> =
    listOf(binary, "-hide_banner", "-i", path)

internal const val PLAYBACK_SAMPLE_RATE: Int = 44_100
internal const val PLAYBACK_CHANNELS: Int = 2

/** Pipe reads can split PCM samples. Preserve frame boundaries and retry partial device writes. */
internal fun copyPcmFrames(
    input: InputStream,
    frameSize: Int,
    active: () -> Boolean,
    write: (ByteArray, Int, Int) -> Int,
) {
    require(frameSize > 0)
    val buffer = ByteArray((16 * 1024 / frameSize).coerceAtLeast(1) * frameSize)
    while (active()) {
        var count = 0
        while (count < buffer.size && active()) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read > 0) count += read
        }
        if (!active() || count == 0) return
        check(count % frameSize == 0) { "Incomplete PCM frame" }
        var offset = 0
        while (offset < count && active()) {
            val written = write(buffer, offset, count - offset)
            if (!active()) return
            check(written > 0 && written <= count - offset && written % frameSize == 0) { "Invalid PCM write" }
            offset += written
        }
    }
}
