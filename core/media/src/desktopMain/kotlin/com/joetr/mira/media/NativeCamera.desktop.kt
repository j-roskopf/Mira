package com.joetr.mira.media

import com.joetr.mira.domain.CaptureQuality

/** Desktops have no system camera UI, so capture stays in-app. */
public actual fun supportsNativeCapture(kind: CaptureKind): Boolean = false

public actual suspend fun captureWithNativeCamera(
    kind: CaptureKind,
    quality: CaptureQuality,
): CaptureResult? = null
