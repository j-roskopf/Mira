package com.joetr.mira.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The ten themes. The accent owns selected days, the today ring, the record button, the active tab,
 * attachment dots, links, the heatmap ramp and focus rings — and nothing else is coloured, ever.
 *
 * OKLCH values are authoritative; the hex in [swatch] is the same colour and exists so the picker
 * does not have to convert on every frame.
 */
public enum class MiraAccent(
    public val displayName: String,
    public val oklch: Oklch,
    public val swatch: Color,
) {
    EMBER("Ember", Oklch(0.645f, 0.182f, 32f), Color(0xFFF0533D)),
    PEACH("Peach", Oklch(0.782f, 0.102f, 54f), Color(0xFFF2A878)),
    SAFFRON("Saffron", Oklch(0.794f, 0.143f, 83f), Color(0xFFE8B23A)),
    MOSS("Moss", Oklch(0.643f, 0.085f, 152f), Color(0xFF5FA87A)),
    TEAL("Teal", Oklch(0.643f, 0.082f, 185f), Color(0xFF3FA8A0)),
    COBALT("Cobalt", Oklch(0.591f, 0.177f, 266f), Color(0xFF4A7DF0)),
    IRIS("Iris", Oklch(0.588f, 0.185f, 288f), Color(0xFF7C6CF0)),
    MAGENTA("Magenta", Oklch(0.631f, 0.177f, 349f), Color(0xFFD95AA8)),
    SLATE("Slate", Oklch(0.627f, 0.033f, 262f), Color(0xFF8A93A8)),
    BONE("Bone", Oklch(0.907f, 0.019f, 85f), Color(0xFFE8E2D6)),
    ;

    public val color: Color get() = swatch

    /**
     * Glyph colour on a filled accent surface. Computed from lightness rather than stored, so a new
     * accent can never ship with unreadable text on it: Peach, Saffron and Bone take dark glyphs,
     * everything else takes light.
     */
    public val onAccent: Color
        get() = if (oklch.l > 0.70f) Color(0xFF0A0A0B) else Color(0xFFFFFFFF)

    /**
     * Five heatmap steps, step 0 being a day with a single entry and step 4 a day at or above the
     * busy threshold. A day with no entries draws no circle at all, which is what makes a consistent
     * month read as a constellation rather than a grid.
     */
    public fun heatmapRamp(dark: Boolean): List<Color> {
        val anchor = if (dark) {
            Oklch(DARK_ANCHOR_L, DARK_ANCHOR_C, oklch.h)
        } else {
            Oklch(LIGHT_ANCHOR_L, LIGHT_ANCHOR_C, oklch.h)
        }
        return (0..4).map { step -> anchor.lerpTo(oklch, step / 4f).toColor() }
    }

    public companion object {
        private const val DARK_ANCHOR_L = 0.28f
        private const val DARK_ANCHOR_C = 0.022f
        private const val LIGHT_ANCHOR_L = 0.93f
        private const val LIGHT_ANCHOR_C = 0.016f

        public val Default: MiraAccent = EMBER

        public fun fromName(name: String?): MiraAccent =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Default
    }
}

/**
 * Picks a ramp step for [entryCount] against a [busyThreshold].
 *
 * The threshold is adaptive — the caller passes the 90th percentile of entries-per-day across the
 * visible month, floored at 3. A fixed threshold would render a typical journalling month entirely
 * at step 0, with no variation to look at.
 */
public fun heatmapStep(entryCount: Int, busyThreshold: Int): Int = when {
    entryCount <= 0 -> -1
    busyThreshold <= 1 -> 4
    else -> {
        val scaled = (entryCount - 1).toFloat() / (busyThreshold - 1).toFloat()
        (scaled * 4f).toInt().coerceIn(0, 4)
    }
}

/** The 90th-percentile busy threshold for a month, floored at [floor]. */
public fun busyThreshold(counts: Collection<Int>, floor: Int = 3): Int {
    val active = counts.filter { it > 0 }.sorted()
    if (active.isEmpty()) return floor
    val index = ((active.size - 1) * 0.9f).toInt()
    return maxOf(active[index], floor)
}

/**
 * The stable colour identity of a tag. Every tag name owns one of the ten accents, so `#walk`
 * reads the same colour on every screen and across launches without storing a colour anywhere.
 */
public fun tagAccent(tag: String): MiraAccent {
    var hash = 0
    for (character in tag.trim().trimStart('#').lowercase()) {
        hash = hash * 31 + character.code
    }
    return MiraAccent.entries[((hash % MiraAccent.entries.size) + MiraAccent.entries.size) % MiraAccent.entries.size]
}

/** Soft tinted surface and matching ink for a tag pill. */
public data class TagTone(val surface: Color, val ink: Color)

/**
 * Derives a tag's pill colours from its accent.
 *
 * The surface is a low-chroma wash at the accent's hue, and the ink keeps that hue at a lightness
 * that clears AA against the surface in both themes. Alpha is not used: over the near-black canvas
 * it would grey the colours into mush.
 */
public fun MiraAccent.tagTone(dark: Boolean): TagTone {
    val surface = Oklch(
        l = if (dark) DARK_TAG_L else LIGHT_TAG_L,
        c = if (dark) DARK_TAG_C else LIGHT_TAG_C,
        h = oklch.h,
    ).toColor()
    val ink = Oklch(
        l = if (dark) DARK_TAG_INK_L else LIGHT_TAG_INK_L,
        c = oklch.c.coerceAtMost(0.16f),
        h = oklch.h,
    ).toColor()
    return TagTone(surface, ink)
}

private const val DARK_TAG_L = 0.31f
private const val DARK_TAG_C = 0.075f
private const val DARK_TAG_INK_L = 0.83f
private const val LIGHT_TAG_L = 0.93f
private const val LIGHT_TAG_C = 0.042f
private const val LIGHT_TAG_INK_L = 0.45f

