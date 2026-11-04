package com.joetr.mira.domain

/**
 * How much detail to keep when capturing new photos and videos.
 *
 * The capture UI offers the same three choices everywhere; the platform decides how to apply them.
 * Native camera apps cannot always be told an exact resolution, so each platform maps these to the
 * closest controls it exposes (recording quality, export preset, or a post-capture photo resize).
 */
public enum class CaptureQuality(public val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
}
