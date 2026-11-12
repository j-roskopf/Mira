package com.joetr.mira.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiraAccentTest {

    @Test
    fun includesTheNewAccents() {
        assertEquals(Color(0xFF41A0B9), MiraAccent.LAGOON.swatch)
        assertEquals(Color(0xFFAE62B9), MiraAccent.PLUM.swatch)
        assertEquals("Lagoon", MiraAccent.LAGOON.displayName)
        assertEquals("Plum", MiraAccent.PLUM.displayName)
        assertEquals(12, MiraAccent.entries.size)
    }

    @Test
    fun everyAccentHasAUniqueNameAndSwatch() {
        assertEquals(MiraAccent.entries.size, MiraAccent.entries.map { it.displayName }.toSet().size)
        assertEquals(MiraAccent.entries.size, MiraAccent.entries.map { it.swatch }.toSet().size)
    }

    @Test
    fun fromNameResolvesEveryAccent() {
        MiraAccent.entries.forEach { accent ->
            assertEquals(accent, MiraAccent.fromName(accent.name))
            assertEquals(accent, MiraAccent.fromName(accent.name.lowercase()))
        }
        assertEquals(MiraAccent.Default, MiraAccent.fromName("not-a-colour"))
    }

    @Test
    fun lightAccentsTakeDarkGlyphs() {
        MiraAccent.entries.filter { it.oklch.l > 0.70f }.forEach { accent ->
            assertEquals(Color(0xFF0A0A0B), accent.onAccent)
        }
        assertTrue(MiraAccent.LAGOON.onAccent == Color.White)
        assertTrue(MiraAccent.PLUM.onAccent == Color.White)
    }
}
