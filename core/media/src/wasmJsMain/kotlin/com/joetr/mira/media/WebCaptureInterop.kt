package com.joetr.mira.media

import kotlinx.coroutines.CancellationException
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
        // The camera preview is opened as soon as the capture screen mounts, so reuse its video
        // track instead of asking the browser for a second camera stream. Only the microphone is
        // acquired here (video-only previews would otherwise prompt for it up front).
        const preview = video
            ? (window.__miraCapturePreview
                || (window.__miraCapturePreviewPromise
                    ? await window.__miraCapturePreviewPromise
                    : null))
            : null;

        let stream;
        let previewEl = null;
        if (video && preview) {
            const microphone = await navigator.mediaDevices.getUserMedia({
                audio: { channelCount: 1 },
            });
            stream = new MediaStream([
                ...preview.stream.getVideoTracks(),
                ...microphone.getAudioTracks(),
            ]);
            previewEl = preview.preview;
            previewEl.srcObject = stream;
            window.__miraCapturePreview = { stream: stream, preview: previewEl };
        } else {
            const constraints = video
                ? { audio: true, video: { width: { ideal: 1280 }, height: { ideal: 720 } } }
                : { audio: { channelCount: 1 } };
            stream = await navigator.mediaDevices.getUserMedia(constraints);
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
                const bounds = window.__miraPreviewBounds;
                if (bounds) {
                    previewEl.style.left = bounds[0] + 'px';
                    previewEl.style.top = bounds[1] + 'px';
                    previewEl.style.width = bounds[2] + 'px';
                    previewEl.style.height = bounds[3] + 'px';
                }
                window.__miraCapturePreview = { stream: stream, preview: previewEl };
            }
        }

        const options = video
            ? { mimeType: 'video/webm', videoBitsPerSecond: 1200000, audioBitsPerSecond: 64000 }
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

        // Live level metering for the recording waveform. Separate from the recorder itself —
        // MediaRecorder exposes no amplitude, so this taps the same stream with a Web Audio analyser.
        let audioCtx = null;
        let analyser = null;
        let levelData = null;
        try {
            audioCtx = new (window.AudioContext || window.webkitAudioContext)();
            const source = audioCtx.createMediaStreamSource(stream);
            analyser = audioCtx.createAnalyser();
            analyser.fftSize = 256;
            source.connect(analyser);
            levelData = new Uint8Array(analyser.frequencyBinCount);
        } catch (e) { }

        return {
            recorder: recorder,
            stream: stream,
            chunks: chunks,
            preview: previewEl,
            audioCtx: audioCtx,
            analyser: analyser,
            levelData: levelData,
        };
    })()
    """,
)

/** Root-mean-square level of the live mic input, 0f..1f, for the recording waveform. */
private fun getWebAmplitudeJs(handle: JsAny): Double = js(
    """
    (() => {
        if (!handle.analyser || !handle.levelData) return 0;
        handle.analyser.getByteTimeDomainData(handle.levelData);
        let sum = 0;
        for (let i = 0; i < handle.levelData.length; i++) {
            const v = (handle.levelData[i] - 128) / 128;
            sum += v * v;
        }
        const rms = Math.sqrt(sum / handle.levelData.length);
        return Math.min(1, rms * 4);
    })()
    """,
)

internal fun getWebAmplitude(handle: JsAny): Float = getWebAmplitudeJs(handle).toFloat()

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
        if (window.__miraCapturePreview
            && window.__miraCapturePreview.preview === handle.preview) {
            window.__miraCapturePreview = null;
        }
        if (handle.audioCtx) { handle.audioCtx.close().catch(() => {}); }
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

/**
 * Opens the camera (no microphone, no recorder) and shows a live `<video>` element, so the feed is
 * visible and permission is requested as soon as the capture screen mounts. [startWebRecording] and
 * [startWebPhotoPreview] reuse this same stream instead of opening the camera a second time.
 *
 * The promise is cached on `window` so a shutter pressed while the permission prompt is still open
 * waits for the same stream rather than racing a second `getUserMedia`.
 */
private fun startWebPreviewJs(): Promise<JsAny?> = js(
    """
    (async () => {
        if (window.__miraCapturePreview) return window.__miraCapturePreview;
        if (window.__miraCapturePreviewPromise) {
            try {
                const pending = await window.__miraCapturePreviewPromise;
                if (pending) return pending;
            } catch (e) { }
        }
        if (window.__miraCapturePreview) return window.__miraCapturePreview;

        // A generation counter lets cancelWebPreview abort a getUserMedia that resolves after the
        // capture screen was dismissed, so closing quickly never leaves the camera running.
        const generation = (window.__miraCaptureGeneration || 0) + 1;
        window.__miraCaptureGeneration = generation;

        const promise = (async () => {
            const stream = await navigator.mediaDevices.getUserMedia({
                video: { width: { ideal: 1920 }, height: { ideal: 1080 } },
            });
            if (window.__miraCaptureGeneration !== generation) {
                stream.getTracks().forEach((t) => t.stop());
                return null;
            }
            const previewEl = document.createElement('video');
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
            // Layout has already run by the time getUserMedia resolves, so onGloballyPositioned
            // will not fire again for the new element. Replay the last known bounds here.
            const bounds = window.__miraPreviewBounds;
            if (bounds) {
                previewEl.style.left = bounds[0] + 'px';
                previewEl.style.top = bounds[1] + 'px';
                previewEl.style.width = bounds[2] + 'px';
                previewEl.style.height = bounds[3] + 'px';
            }
            const handle = { stream: stream, preview: previewEl };
            window.__miraCapturePreview = handle;
            return handle;
        })();

        window.__miraCapturePreviewPromise = promise;
        try {
            return await promise;
        } finally {
            if (window.__miraCapturePreviewPromise === promise) {
                window.__miraCapturePreviewPromise = null;
            }
        }
    })()
    """,
)

internal suspend fun startWebPreview(): JsAny? = try {
    startWebPreviewJs().await<JsAny?>()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    null
}

internal fun cancelWebPreview() {
    js(
        """
        try {
            window.__miraCaptureGeneration = (window.__miraCaptureGeneration || 0) + 1;
            window.__miraCapturePreviewPromise = null;
            const preview = window.__miraCapturePreview;
            window.__miraCapturePreview = null;
            if (preview) {
                preview.stream.getTracks().forEach((t) => t.stop());
                if (preview.preview) { preview.preview.remove(); }
            }
        } catch (e) { }
        """,
    )
}

/** Reuses the framing preview when one is already open, so a photo does not restart the camera. */
internal suspend fun startWebPhotoPreview(): JsAny? = startWebPreview()

/** Snapshots the live preview's current frame to a JPEG, then releases the camera. */
private fun capturePhotoFromPreviewJs(handle: JsAny): Promise<ArrayBuffer?> = js(
    """
    (async () => {
        const video = handle.preview;
        if (!video) return null;
        if (video.videoWidth === 0) {
            await new Promise((resolve) => {
                const check = () => { if (video.videoWidth > 0) resolve(); else requestAnimationFrame(check); };
                check();
            });
        }
        const canvas = document.createElement('canvas');
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
        handle.stream.getTracks().forEach((t) => t.stop());
        video.remove();
        if (window.__miraCapturePreview
            && window.__miraCapturePreview.preview === video) {
            window.__miraCapturePreview = null;
        }
        const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92));
        if (!blob) return null;
        return await blob.arrayBuffer();
    })()
    """,
)

internal suspend fun capturePhotoFromPreview(handle: JsAny): ByteArray? {
    val buffer = runCatching { capturePhotoFromPreviewJs(handle).await<ArrayBuffer?>() }.getOrNull()
        ?: return null
    return buffer.toByteArray()
}

internal fun cancelWebRecording(handle: JsAny) {
    js(
        """
        try {
            if (handle.recorder && handle.recorder.state !== 'inactive') handle.recorder.stop();
            if (handle.stream) handle.stream.getTracks().forEach((t) => t.stop());
            if (handle.preview) { handle.preview.remove(); }
            if (window.__miraCapturePreview
                && window.__miraCapturePreview.preview === handle.preview) {
                window.__miraCapturePreview = null;
            }
            if (handle.audioCtx) { handle.audioCtx.close().catch(() => {}); }
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
            const core = 'https://cdn.jsdelivr.net/npm/@ffmpeg/core@0.12.6/dist/esm';

            // The FFmpeg class creates its worker with `new Worker(new URL('./worker.js',
            // import.meta.url))`. Because the class itself is imported from a CDN, that resolves
            // to a cross-origin URL, which browsers refuse to construct a worker from. Fetch the
            // worker, rewrite its relative imports to absolute CDN URLs, and hand it over as a
            // same-origin blob URL instead.
            if (!window.__miraFfmpegWorkerURL) {
                const workerUrl = 'https://cdn.jsdelivr.net/npm/@ffmpeg/ffmpeg@0.12.10/dist/esm/worker.js';
                const source = await (await fetch(workerUrl)).text();
                const patched = source.replace(
                    /(\bfrom\s*)(["'])(\.{1,2}\/[^"']+)\2/g,
                    (match, keyword, quote, specifier) =>
                        keyword + quote + new URL(specifier, workerUrl).href + quote,
                );
                window.__miraFfmpegWorkerURL = URL.createObjectURL(
                    new Blob([patched], { type: 'text/javascript' }),
                );
            }

            await ffmpeg.load({
                classWorkerURL: window.__miraFfmpegWorkerURL,
                coreURL: await util.toBlobURL(core + '/ffmpeg-core.js', 'text/javascript'),
                wasmURL: await util.toBlobURL(core + '/ffmpeg-core.wasm', 'application/wasm'),
            });
            window.__miraFfmpeg = ffmpeg;
        }
        const ffmpeg = window.__miraFfmpeg;
        const outName = video ? 'out.mp4' : 'out.m4a';
        await ffmpeg.writeFile('in.webm', new Uint8Array(input.buffer));
        const args = video
            ? ['-i', 'in.webm', '-c:v', 'libx264', '-preset', 'veryfast', '-b:v', '1200000',
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
        window.__miraPreviewBounds = [x, y, width, height];
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
