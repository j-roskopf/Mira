package com.joetr.mira.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public actual suspend fun exportFile(suggestedName: String, mimeType: String, bytes: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        pickDesktopSaveFile(
            title = "Save Mira export",
            defaultFileName = suggestedName,
            bytes = bytes,
            extensions = setOf(suggestedName.substringAfterLast('.', "zip")),
        )
    }

public actual suspend fun importFile(extensions: Set<String>): ByteArray? =
    withContext(Dispatchers.IO) {
        pickDesktopOpenFile(title = "Choose a Mira export", extensions = extensions)
    }
