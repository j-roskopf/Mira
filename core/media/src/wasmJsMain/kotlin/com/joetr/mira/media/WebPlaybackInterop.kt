package com.joetr.mira.media

import org.khronos.webgl.Int8Array

/**
 * The `<audio>` / `<video>` element Mira drives for playback.
 *
 * Video elements are appended to the document and positioned over the region Compose reserved,
 * because a canvas-rendered Compose tree cannot draw decoded video frames itself. Audio elements
 * are never visible.
 */
internal fun createPlayerElement(id: String, url: String, video: Boolean) {
    js(
        """
        const existing = document.getElementById(id);
        if (existing) existing.remove();
        const el = document.createElement(video ? 'video' : 'audio');
        el.id = id;
        el.src = url;
        el.preload = 'metadata';
        el.playsInline = true;
        if (video) {
            el.style.position = 'fixed';
            el.style.objectFit = 'contain';
            el.style.background = '#000';
            el.style.pointerEvents = 'none';
            el.style.zIndex = '1';
            el.style.width = '0px';
            el.style.height = '0px';
        } else {
            el.style.display = 'none';
        }
        document.body.appendChild(el);
        """,
    )
}

internal fun positionPlayerElement(
    id: String,
    x: Double,
    y: Double,
    width: Double,
    height: Double,
) {
    js(
        """
        const el = document.getElementById(id);
        if (el && el.tagName === 'VIDEO') {
            el.style.left = x + 'px';
            el.style.top = y + 'px';
            el.style.width = width + 'px';
            el.style.height = height + 'px';
        }
        """,
    )
}

internal fun removePlayerElement(id: String) {
    js(
        """
        const el = document.getElementById(id);
        if (el) {
            try { el.pause(); } catch (e) { }
            if (el.src && el.src.startsWith('blob:')) URL.revokeObjectURL(el.src);
            el.remove();
        }
        """,
    )
}

internal fun playPlayer(id: String) {
    js(
        """
        const el = document.getElementById(id);
        if (el) { const p = el.play(); if (p && p.catch) p.catch(() => {}); }
        """,
    )
}

internal fun pausePlayer(id: String) {
    js("const el = document.getElementById(id); if (el) el.pause();")
}

internal fun seekPlayer(id: String, seconds: Double) {
    js("const el = document.getElementById(id); if (el) el.currentTime = seconds;")
}

internal fun playerPositionMs(id: String): Double =
    js("(() => { const el = document.getElementById(id); return el ? el.currentTime * 1000 : 0; })()")

internal fun playerDurationMs(id: String): Double = js(
    """
    (() => {
        const el = document.getElementById(id);
        if (!el || !isFinite(el.duration)) return 0;
        return el.duration * 1000;
    })()
    """,
)

internal fun playerEnded(id: String): Boolean =
    js("(() => { const el = document.getElementById(id); return el ? el.ended : false; })()")

/** Wraps in-memory bytes as a blob URL, for media that has not reached Storage yet. */
internal fun createObjectUrl(bytes: Int8Array, mime: String): String = js(
    "URL.createObjectURL(new Blob([new Uint8Array(bytes.buffer)], { type: mime }))",
)

internal fun ByteArray.toUint8Array(): Int8Array {
    val array = Int8Array(size)
    for (index in indices) setPlaybackByte(array, index, this[index])
    return array
}

private fun setPlaybackByte(array: Int8Array, index: Int, value: Byte) {
    js("array[index] = value")
}
