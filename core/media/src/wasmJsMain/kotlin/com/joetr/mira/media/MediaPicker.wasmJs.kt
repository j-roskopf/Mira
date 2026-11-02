package com.joetr.mira.media

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import kotlin.js.Promise

/** A synthetic `<input type="file">`, the same interop style `ExportFiles.wasm.kt` uses. */
public actual suspend fun pickMedia(allowMultiple: Boolean): List<CaptureResult> {
    val picked = runCatching { pickMediaFilesJs(allowMultiple).await<JsAny?>() }.getOrNull() ?: return emptyList()
    val results = mutableListOf<CaptureResult>()
    for (index in 0 until pickedCount(picked)) {
        val mime = pickedMime(picked, index)
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) continue
        val buffer = pickedBuffer(picked, index) ?: continue
        val bytes = buffer.toByteArray()
        results.add(
            CaptureResult(
                kind = if (mime.startsWith("video/")) CaptureKind.VIDEO else CaptureKind.PHOTO,
                path = null,
                bytes = bytes,
                mime = mime,
                sizeBytes = bytes.size.toLong(),
                durationMs = 0,
            ),
        )
    }
    return results
}

/**
 * Opens a synthetic file input and resolves with an array of `{ mime, buffer }`, read up front
 * since the browser's `File` objects are only valid while the input element is alive.
 */
private fun pickMediaFilesJs(allowMultiple: Boolean): Promise<JsAny?> = js(
    """
    (() => {
        return new Promise((resolve) => {
            const input = document.createElement('input');
            input.type = 'file';
            input.accept = 'image/*,video/*';
            input.multiple = allowMultiple;
            input.style.display = 'none';
            document.body.appendChild(input);
            let settled = false;
            const finish = async (fileList) => {
                if (settled) return;
                settled = true;
                const files = Array.from(fileList || []);
                const items = await Promise.all(files.map(async (f) => ({
                    mime: f.type || '',
                    buffer: await f.arrayBuffer(),
                })));
                input.remove();
                resolve(items);
            };
            input.onchange = () => finish(input.files);
            // No 'cancel' event fires reliably on every browser; a focus check after the dialog
            // closes is the standard workaround for detecting the user dismissed it unchanged.
            window.addEventListener('focus', function onFocus() {
                window.removeEventListener('focus', onFocus);
                setTimeout(() => { if (!settled) finish(null); }, 300);
            });
            input.click();
        });
    })()
    """,
)

private fun pickedCount(picked: JsAny): Int = js("picked.length")
private fun pickedMime(picked: JsAny, index: Int): String = js("picked[index].mime")
private fun pickedBuffer(picked: JsAny, index: Int): ArrayBuffer? = js("picked[index].buffer")

private fun ArrayBuffer.toByteArray(): ByteArray {
    val view = Int8Array(this)
    return ByteArray(view.length) { index -> view[index] }
}
