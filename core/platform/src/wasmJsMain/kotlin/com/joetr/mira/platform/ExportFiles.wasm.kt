package com.joetr.mira.platform

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The browser has no save dialog for arbitrary bytes — a `Blob` + object URL behind a synthetic
 * `<a download>` click drops the file straight into Downloads, which is the web-native equivalent.
 */
private val exportFileWeb: (String, String, String, (Boolean) -> Unit) -> Unit = js(
    """
    (base64, suggestedName, mimeType, onResult) => {
        try {
            const binary = atob(base64);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
            const blob = new Blob([bytes], { type: mimeType });
            const url = URL.createObjectURL(blob);
            const anchor = document.createElement('a');
            anchor.href = url;
            anchor.download = suggestedName;
            document.body.appendChild(anchor);
            anchor.click();
            document.body.removeChild(anchor);
            URL.revokeObjectURL(url);
            onResult(true);
        } catch (e) {
            onResult(false);
        }
    }
    """,
)

private val importFileWeb: ((String) -> Unit) -> Unit = js(
    """
    (onResult) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.zip';
        input.style.display = 'none';
        input.addEventListener('change', () => {
            const file = input.files && input.files[0];
            document.body.removeChild(input);
            if (!file) { onResult(''); return; }
            const reader = new FileReader();
            reader.onload = () => {
                const bytes = new Uint8Array(reader.result);
                let binary = '';
                for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                onResult(btoa(binary));
            };
            reader.onerror = () => onResult('');
            reader.readAsArrayBuffer(file);
        }, { once: true });
        document.body.appendChild(input);
        input.click();
    }
    """,
)

@OptIn(ExperimentalEncodingApi::class)
public actual suspend fun exportFile(suggestedName: String, mimeType: String, bytes: ByteArray): Boolean =
    suspendCoroutine { continuation ->
        val base64 = Base64.Default.encode(bytes)
        exportFileWeb(base64, suggestedName, mimeType, continuation::resume)
    }

@OptIn(ExperimentalEncodingApi::class)
public actual suspend fun importFile(extensions: Set<String>): ByteArray? =
    suspendCoroutine { continuation ->
        importFileWeb { base64 ->
            continuation.resume(if (base64.isEmpty()) null else Base64.Default.decode(base64))
        }
    }
