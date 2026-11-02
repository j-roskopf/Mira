package com.joetr.mira.media

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import kotlin.js.Promise

internal fun hasMediaDevices(): Boolean =
    js("typeof navigator !== 'undefined' && !!navigator.mediaDevices && !!window.MediaRecorder")

internal fun currentTimeMs(): Double = js("Date.now()")

/**
 * Opens the microphone (and camera, for video), starts a `MediaRecorder`, and returns a handle.
 *
 * The recorder is kept entirely on the JS side — Kotlin only holds an opaque reference until the
 * recording stops.
 */
private fun startWebRecordingJs(video: Boolean): Promise<JsAny?> = js(
    """
    (async () => {
        const constraints = video
            ? { audio: true, video: { width: { ideal: 1920 }, height: { ideal: 1080 } } }
            : { audio: { channelCount: 1 } };
        const stream = await navigator.mediaDevices.getUserMedia(constraints);
        const options = video
            ? { mimeType: 'video/webm', videoBitsPerSecond: 4000000, audioBitsPerSecond: 64000 }
            : { mimeType: 'audio/webm', audioBitsPerSecond: 64000 };
        let recorder;
        try {
            recorder = new MediaRecorder(stream, options);
        } catch (e) {
            recorder = new MediaRecorder(stream);
        }
        const chunks = [];
        recorder.ondataavailable = (e) => { if (e.data && e.data.size > 0) chunks.push(e.data); };
        recorder.start(1000);

        let previewEl = null;
        if (video) {
            previewEl = document.createElement('video');
            previewEl.id = 'mira-capture-preview';
            previewEl.autoplay = true;
            previewEl.muted = true;
            previewEl.playsInline = true;
            previewEl.srcObject = stream;
            previewEl.style.position = 'fixed';
            previewEl.style.pointerEvents = 'none';
            previewEl.style.objectFit = 'cover';
            previewEl.style.zIndex = '1';
            document.body.appendChild(previewEl);
        }
        return { recorder: recorder, stream: stream, chunks: chunks, preview: previewEl };
    })()
    """,
)

internal suspend fun startWebRecording(video: Boolean): JsAny? =
    runCatching { startWebRecordingJs(video).await<JsAny?>() }.getOrNull()

private fun stopWebRecordingJs(handle: JsAny): Promise<ArrayBuffer?> = js(
    """
    (async () => {
        const done = new Promise((resolve) => { handle.recorder.onstop = resolve; });
        handle.recorder.stop();
        await done;
        handle.stream.getTracks().forEach((t) => t.stop());
        if (handle.preview) { handle.preview.remove(); }
        const blob = new Blob(handle.chunks, { type: handle.recorder.mimeType });
        return await blob.arrayBuffer();
    })()
    """,
)

internal suspend fun stopWebRecording(handle: JsAny): ByteArray? {
    val buffer = runCatching { stopWebRecordingJs(handle).await<ArrayBuffer?>() }.getOrNull()
        ?: return null
    return buffer.toByteArray()
}

internal fun cancelWebRecording(handle: JsAny) {
    js(
        """
        try {
            if (handle.recorder && handle.recorder.state !== 'inactive') handle.recorder.stop();
            handle.stream.getTracks().forEach((t) => t.stop());
            if (handle.preview) { handle.preview.remove(); }
        } catch (e) { }
        """,
    )
}

/**
 * Transcodes a webm recording to mp4/H.264 or m4a/AAC using ffmpeg.wasm.
 *
 * Loaded from the CDN on first use rather than bundled, so the ~25 MB payload never affects first
 * paint — it is fetched only once someone actually presses record. This is what keeps Mira free of
 * Cloud Functions while still guaranteeing that media recorded in a browser opens on an iPhone.
 */
private fun transcodeJs(input: Int8Array, video: Boolean): Promise<ArrayBuffer?> = js(
    """
    (async () => {
        if (!window.__miraFfmpeg) {
            const mod = await import(/* webpackIgnore: true */ 'https://cdn.jsdelivr.net/npm/@ffmpeg/ffmpeg@0.12.10/+esm');
            const util = await import(/* webpackIgnore: true */ 'https://cdn.jsdelivr.net/npm/@ffmpeg/util@0.12.1/+esm');
            const ffmpeg = new mod.FFmpeg();
            const base = 'https://cdn.jsdelivr.net/npm/@ffmpeg/core@0.12.6/dist/esm';
            await ffmpeg.load({
                coreURL: await util.toBlobURL(base + '/ffmpeg-core.js', 'text/javascript'),
                wasmURL: await util.toBlobURL(base + '/ffmpeg-core.wasm', 'application/wasm'),
            });
            window.__miraFfmpeg = ffmpeg;
        }
        const ffmpeg = window.__miraFfmpeg;
        const outName = video ? 'out.mp4' : 'out.m4a';
        await ffmpeg.writeFile('in.webm', new Uint8Array(input.buffer));
        const args = video
            ? ['-i', 'in.webm', '-c:v', 'libx264', '-preset', 'veryfast', '-b:v', '4000000',
               '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '64000', '-ac', '1', outName]
            : ['-i', 'in.webm', '-c:a', 'aac', '-b:a', '64000', '-ac', '1', outName];
        await ffmpeg.exec(args);
        const data = await ffmpeg.readFile(outName);
        await ffmpeg.deleteFile('in.webm');
        await ffmpeg.deleteFile(outName);
        return data.buffer;
    })()
    """,
)

internal suspend fun transcodeToMp4(bytes: ByteArray, video: Boolean): ByteArray? {
    val input = bytes.toInt8Array()
    val buffer = runCatching { transcodeJs(input, video).await<ArrayBuffer?>() }.getOrNull()
        ?: return null
    return buffer.toByteArray()
}

/** Positions the JS-owned `<video>` preview over the space Compose reserved for it. */
internal fun positionWebPreview(x: Double, y: Double, width: Double, height: Double) {
    js(
        """
        const el = document.getElementById('mira-capture-preview');
        if (el) {
            el.style.left = x + 'px';
            el.style.top = y + 'px';
            el.style.width = width + 'px';
            el.style.height = height + 'px';
        }
        """,
    )
}

private fun ArrayBuffer.toByteArray(): ByteArray {
    val view = Int8Array(this)
    return ByteArray(view.length) { index -> view[index] }
}

private fun ByteArray.toInt8Array(): Int8Array {
    val array = Int8Array(size)
    for (index in indices) setByte(array, index, this[index])
    return array
}

private fun setByte(array: Int8Array, index: Int, value: Byte) {
    js("array[index] = value")
}
