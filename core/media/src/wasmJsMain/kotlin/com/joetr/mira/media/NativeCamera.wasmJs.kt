package com.joetr.mira.media

import com.joetr.mira.domain.CaptureQuality

/** Browsers do not expose a system camera UI that returns media; capture stays in-app. */
public actual fun supportsNativeCapture(kind: CaptureKind): Boolean = false

public actual suspend fun captureWithNativeCamera(
    kind: CaptureKind,
    quality: CaptureQuality,
): CaptureResult? = null
