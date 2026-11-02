package com.joetr.mira.platform

/**
 * Lets the user save [bytes] somewhere they choose — a save dialog on desktop, a "Save to…" sheet
 * on Android/iOS, the Downloads folder on web. Returns whether it was actually saved; false means
 * the user cancelled, not that something went wrong.
 */
public expect suspend fun exportFile(suggestedName: String, mimeType: String, bytes: ByteArray): Boolean

/**
 * Lets the user choose a file to read back in. Returns null if they cancelled.
 *
 * [extensions] is a hint, not a hard filter everywhere — some platform pickers (Android's SAF in
 * particular) don't reliably filter by extension, so callers must still validate what comes back.
 */
public expect suspend fun importFile(extensions: Set<String>): ByteArray?
