package com.joetr.mira.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Compose desktop window used as the parent for native open dialogs (NSOpenPanel on macOS).
 */
public var desktopFileDialogOwner: Frame? = null

/**
 * Must run on the main thread before AWT/Swing initializes so native sheets match macOS appearance.
 */
public fun configureMacOsDesktopAppearance() {
    if (!isMacOs) return
    System.setProperty("apple.awt.application.appearance", "system")
}

/**
 * Opens the platform-native file picker (`NSOpenPanel` on macOS, system dialog on Windows/Linux).
 */
public fun pickDesktopOpenFile(
    title: String,
    extensions: Set<String>,
): ByteArray? {
    applyMacOsAppearanceBeforeNativeDialog()
    val dialog = FileDialog(desktopFileDialogOwner, title, FileDialog.LOAD)
    if (extensions.isNotEmpty()) {
        val normalized = extensions.map { it.lowercase().removePrefix(".") }.toSet()
        dialog.setFilenameFilter { _, name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            normalized.contains(ext)
        }
    }
    dialog.isVisible = true
    val selected = dialog.file
    val directory = dialog.directory
    if (selected == null || directory == null) return null
    return runCatching { File(directory, selected).readBytes() }.getOrNull()
}

/**
 * Opens the platform-native save dialog and writes [bytes] to the selected path.
 */
public fun pickDesktopSaveFile(
    title: String,
    defaultFileName: String,
    bytes: ByteArray,
    extensions: Set<String>,
): Boolean {
    applyMacOsAppearanceBeforeNativeDialog()
    val dialog = FileDialog(desktopFileDialogOwner, title, FileDialog.SAVE)
    dialog.file = defaultFileName
    if (extensions.isNotEmpty()) {
        val normalized = extensions.map { it.lowercase().removePrefix(".") }.toSet()
        dialog.setFilenameFilter { _, name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            normalized.contains(ext)
        }
    }
    dialog.isVisible = true
    val selected = dialog.file
    val directory = dialog.directory
    if (selected == null || directory == null) return false
    val target = if (extensions.isNotEmpty() && !selected.contains('.')) {
        val ext = extensions.first().removePrefix(".")
        File(directory, "$selected.$ext")
    } else {
        File(directory, selected)
    }
    return runCatching {
        target.writeBytes(bytes)
        true
    }.getOrElse { false }
}

private val isMacOs: Boolean =
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

private fun applyMacOsAppearanceBeforeNativeDialog() {
    if (!isMacOs) return
    runCatching {
        val appClass = Class.forName("com.apple.eawt.Application")
        val appearanceClass = Class.forName("com.apple.eawt.Application\$Appearance")
        val systemAppearance = java.lang.Enum.valueOf(
            appearanceClass as Class<out Enum<*>>,
            "SYSTEM",
        )
        val app = appClass.getMethod("getApplication").invoke(null)
        app.javaClass.getMethod("setAppearance", appearanceClass).invoke(app, systemAppearance)
    }
}
