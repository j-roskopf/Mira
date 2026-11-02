package com.joetr.mira.updates

import com.joetr.mira.updates.UpdatePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateAssetSelectionTest {
    @Test
    fun selectsFlatpakAssetForFlatpakInstalls() {
        val asset = selectUpdateAsset(
            UpdatePlatform.LinuxFlatpak,
            listOf(
                GitHubAssetDto("Mira-1.2.3.deb", "https://example.test/mira.deb", state = "uploaded"),
                GitHubAssetDto("Mira-1.2.3.flatpak", "https://example.test/mira.flatpak", state = "uploaded"),
            ),
        )

        assertEquals("Mira-1.2.3.flatpak", asset?.name)
    }

    @Test
    fun flatpakInstallerEscapesToHostAndRelaunchesFlatpak() {
        val script = linuxInstallerHelperScript(
            filePath = "/home/ada/Downloads/Mira-1.2.3.flatpak",
            flatpak = true,
            insideFlatpak = true,
            relaunchCommand = "flatpak run com.joetr.mira",
        )

        assertTrue(script.contains("flatpak-spawn --host sh -c"))
        assertTrue(script.contains("flatpak install --user -y"))
        assertTrue(script.contains("flatpak run com.joetr.mira"))
    }

    @Test
    fun macDmgInstallerReplacesAndRelaunchesAppBundle() {
        val script = macDmgInstallerHelperScript(
            dmgPath = "/tmp/Mira-1.2.3.dmg",
            targetAppBundle = "/Applications/Mira.app",
            parentProcessId = 1234,
        )

        assertTrue(script.contains("/bin/mkdir -p \"${'$'}mount_point\""))
        assertTrue(script.contains("/usr/bin/hdiutil attach \"${'$'}dmg_path\""))
        assertTrue(script.contains("/usr/bin/open \"${'$'}target_app\""))
    }

    @Test
    fun macPkgInstallerOpensPkgAndRelaunchesAfterItCloses() {
        val script = macPkgInstallerHelperScript(
            pkgPath = "/Users/ada/Downloads/Mira-1.2.3.pkg",
        )

        assertTrue(script.contains("/usr/bin/open -W '/Users/ada/Downloads/Mira-1.2.3.pkg'"))
        assertTrue(script.contains("/usr/bin/open -b 'com.joetr.mira' || /usr/bin/open -a 'Mira'"))
    }

    @Test
    fun selectsPkgWhenNoDmgIsPublished() {
        val asset = selectUpdateAsset(
            UpdatePlatform.MacOs,
            listOf(
                GitHubAssetDto("Mira-1.2.3.pkg", "https://example.test/Mira.pkg", state = "uploaded"),
            ),
        )

        assertEquals("Mira-1.2.3.pkg", asset?.name)
    }
}
