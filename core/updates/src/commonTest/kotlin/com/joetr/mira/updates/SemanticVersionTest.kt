package com.joetr.mira.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticVersionTest {
    @Test
    fun parsesPlainAndPrefixedVersions() {
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("1.2.3"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("v1.2.3"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("release/1.2.3"))
    }

    @Test
    fun comparesSemverOrdering() {
        assertTrue(SemanticVersion(1, 2, 3) < SemanticVersion(1, 2, 4))
        assertTrue(SemanticVersion(1, 2, 3) > SemanticVersion(1, 1, 9))
    }

    @Test
    fun rejectsInvalidVersions() {
        assertNull(SemanticVersion.parse("not-a-version"))
    }
}
