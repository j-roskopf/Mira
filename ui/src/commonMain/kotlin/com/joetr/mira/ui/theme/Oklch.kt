package com.joetr.mira.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A colour in OKLCH: perceptual lightness, chroma, hue.
 *
 * Mira derives the heatmap ramp here rather than by fading the accent with alpha. Alpha over a
 * near-black canvas pulls every step toward grey, so the five levels end up muddy and perceptually
 * uneven — the exact failure the heatmap cannot afford, since it is the screen the app is judged on.
 * Interpolating lightness and chroma in OKLCH at constant hue gives evenly spaced steps that stay
 * recognisably the accent colour.
 */
public data class Oklch(val l: Float, val c: Float, val h: Float) {

    public fun toColor(): Color {
        val hueRad = (h * PI / 180.0).toFloat()
        val a = c * cos(hueRad)
        val b = c * sin(hueRad)

        val lp = l + 0.3963377774f * a + 0.2158037573f * b
        val mp = l - 0.1055613458f * a - 0.0638541728f * b
        val sp = l - 0.0894841775f * a - 1.2914855480f * b

        val lc = lp * lp * lp
        val mc = mp * mp * mp
        val sc = sp * sp * sp

        val r = 4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc
        val g = -1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc
        val bl = -0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc

        return Color(
            red = linearToSrgb(r),
            green = linearToSrgb(g),
            blue = linearToSrgb(bl),
        )
    }

    public fun lerpTo(other: Oklch, t: Float): Oklch = Oklch(
        l = l + (other.l - l) * t,
        c = c + (other.c - c) * t,
        h = other.h,
    )

    public companion object {
        public fun fromColor(color: Color): Oklch {
            val r = srgbToLinear(color.red)
            val g = srgbToLinear(color.green)
            val b = srgbToLinear(color.blue)

            val lc = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
            val mc = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
            val sc = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)

            val l = 0.2104542553f * lc + 0.7936177850f * mc - 0.0040720468f * sc
            val a = 1.9779984951f * lc - 2.4285922050f * mc + 0.4505937099f * sc
            val bb = 0.0259040371f * lc + 0.7827717662f * mc - 0.8086757660f * sc

            val chroma = kotlin.math.sqrt(a * a + bb * bb)
            var hue = (kotlin.math.atan2(bb, a) * 180.0 / PI).toFloat()
            if (hue < 0f) hue += 360f
            return Oklch(l, chroma, hue)
        }
    }
}

private fun linearToSrgb(value: Float): Float {
    val v = value.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
}

private fun srgbToLinear(value: Float): Float =
    if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
