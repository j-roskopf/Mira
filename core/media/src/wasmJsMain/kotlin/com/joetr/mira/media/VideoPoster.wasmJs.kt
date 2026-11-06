package com.joetr.mira.media

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import kotlin.js.Promise

/**
 * Decodes the frame in a detached `<video>` element and paints it to a canvas.
 *
 * Web has no local file for an uploaded attachment, but staged bytes are still in memory, and a
 * browser can decode those without touching the network.
 */
public actual suspend fun extractVideoPoster(
    path: String?,
    bytes: ByteArray?,
    mime: String,
): ByteArray? {
    val data = bytes ?: return null
    val buffer = runCatching {
        posterFromBytesJs(data.toUint8Array(), mime).await<ArrayBuffer?>()
    }.getOrNull() ?: return null
    return buffer.toPosterByteArray()
}

private fun posterFromBytesJs(bytes: Int8Array, mime: String): Promise<ArrayBuffer?> = js(
    """
    (async () => {
        const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
        const video = document.createElement('video');
        video.preload = 'auto';
        video.muted = true;
        video.playsInline = true;
        video.src = url;
        try {
            await new Promise((resolve, reject) => {
                const timeout = setTimeout(() => reject(new Error('poster timeout')), 15000);
                video.onloadeddata = () => { clearTimeout(timeout); resolve(); };
                video.onerror = () => { clearTimeout(timeout); reject(new Error('poster decode')); };
            });
            const width = video.videoWidth;
            const height = video.videoHeight;
            if (!width || !height) return null;

            const duration = isFinite(video.duration) ? video.duration : 0;
            if (duration > 0.3) {
                video.currentTime = 0.1;
                await new Promise((resolve) => {
                    const timeout = setTimeout(resolve, 1000);
                    video.onseeked = () => { clearTimeout(timeout); resolve(); };
                });
            }

            const scale = Math.min(1, 640 / Math.max(width, height));
            const canvas = document.createElement('canvas');
            canvas.width = Math.max(1, Math.round(width * scale));
            canvas.height = Math.max(1, Math.round(height * scale));
            canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
            const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.85));
            if (!blob) return null;
            return await blob.arrayBuffer();
        } finally {
            video.removeAttribute('src');
            video.load();
            URL.revokeObjectURL(url);
        }
    })()
    """,
)

private fun ArrayBuffer.toPosterByteArray(): ByteArray {
    val view = Int8Array(this)
    return ByteArray(view.length) { index -> view[index] }
}
