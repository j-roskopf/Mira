package com.joetr.mira.media

/**
 * Opens the platform's native photo/video picker (Android's Photo Picker, iOS's `PHPicker`, a
 * desktop file dialog, or a browser file input) and returns whatever the user selected.
 *
 * This never records anything — it only reads bytes the user already has — so each result's
 * [CaptureResult.kind] is decided from the picked item's MIME type (`PHOTO` or `VIDEO`), and
 * [CaptureResult.durationMs] is probed for video, `0` for a photo. An empty list means the user
 * cancelled, not that something failed.
 */
public expect suspend fun pickMedia(allowMultiple: Boolean = true): List<CaptureResult>
