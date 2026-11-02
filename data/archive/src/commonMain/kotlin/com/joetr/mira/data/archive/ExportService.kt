package com.joetr.mira.data.archive

import com.joetr.mira.data.entries.EntryRepository
import com.joetr.mira.data.entries.media.MediaRepository
import com.joetr.mira.domain.CURRENT_EXPORT_SCHEMA_VERSION
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.ExportBundle
import com.joetr.mira.domain.ExportManifest
import com.joetr.mira.domain.toEntry
import com.joetr.mira.domain.toExportEntry
import com.joetr.mira.platform.MiraZip
import com.joetr.mira.platform.currentTimeMillis
import kotlinx.serialization.json.Json

public data class ImportResult(
    val entriesRestored: Int,
    val entriesSkipped: Int,
    val mediaRestored: Int,
)

public class ImportFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val bundleJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

private const val BUNDLE_PATH = "export.json"
private const val EXPORT_PAGE_SIZE = 500

/**
 * Full export/import of the journal, independent of the one-way GitHub archive.
 *
 * The bundle is a single zip: [BUNDLE_PATH] holds a versioned, structured [ExportBundle] — the
 * "hard schema" import actually parses — alongside a human-readable Markdown mirror of the GitHub
 * archive layout (same `entries/` and `media/` paths, via [Entry.toMarkdown]) for anyone who just
 * wants to read their journal without Mira. Unlike the GitHub archive, every attachment's bytes are
 * always included — there is no "include media" toggle for a local backup.
 *
 * Import merges into whichever account is signed in, applying the same last-write-wins rule as sync
 * ([EntryRepository.restore]) so importing an old backup can't clobber newer edits.
 */
public class ExportService(
    private val entries: EntryRepository,
    private val media: MediaRepository,
    private val appVersion: String,
) {
    public suspend fun export(ownerId: String): ByteArray {
        val all = mutableListOf<Entry>()
        var offset = 0
        while (true) {
            val page = entries.timeline(ownerId, limit = EXPORT_PAGE_SIZE, offset = offset)
            all.addAll(page)
            offset += page.size
            if (page.size < EXPORT_PAGE_SIZE) break
        }

        val parts = mutableListOf<Pair<String, ByteArray>>()
        val mediaPathsByEntry = HashMap<String, Map<String, String>>()
        var attachmentCount = 0

        for (entry in all) {
            val pathsForEntry = HashMap<String, String>()
            for (attachment in entry.attachments) {
                val bytes = media.bytesFor(attachment) ?: continue
                val path = attachment.archiveMediaPath(entry)
                parts.add(path to bytes)
                pathsForEntry[attachment.id] = path
                attachmentCount++
            }
            mediaPathsByEntry[entry.id] = pathsForEntry
            parts.add(entry.archivePath() to entry.toMarkdown(pathsForEntry.values.toSet()).encodeToByteArray())
        }

        val exportEntries = all.map { it.toExportEntry(mediaPathsByEntry[it.id].orEmpty()) }
        val bundle = ExportBundle(
            manifest = ExportManifest(
                schemaVersion = CURRENT_EXPORT_SCHEMA_VERSION,
                exportedAt = currentTimeMillis(),
                appVersion = appVersion,
                entryCount = exportEntries.size,
                attachmentCount = attachmentCount,
            ),
            entries = exportEntries,
        )
        parts.add(0, BUNDLE_PATH to bundleJson.encodeToString(ExportBundle.serializer(), bundle).encodeToByteArray())

        return MiraZip.write(parts)
    }

    public suspend fun import(ownerId: String, zipBytes: ByteArray): ImportResult {
        val files = try {
            MiraZip.read(zipBytes)
        } catch (error: IllegalArgumentException) {
            throw ImportFailedException(error.message ?: "Not a Mira export file.", error)
        }
        val bundleBytes = files[BUNDLE_PATH]
            ?: throw ImportFailedException("Not a Mira export file: missing $BUNDLE_PATH.")
        val bundle = try {
            bundleJson.decodeFromString(ExportBundle.serializer(), bundleBytes.decodeToString())
        } catch (error: Exception) {
            throw ImportFailedException("Could not read this export file.", error)
        }

        // The extension point for future schema versions: add a case here with its own parsing,
        // never change what schema version 1 means once it has shipped.
        val parsedEntries = when (bundle.manifest.schemaVersion) {
            1 -> bundle.entries
            else -> throw ImportFailedException(
                "This export was made by a newer version of Mira (schema " +
                    "${bundle.manifest.schemaVersion}). Update Mira before importing it.",
            )
        }

        var restored = 0
        var skipped = 0
        var mediaRestored = 0
        for (exportEntry in parsedEntries) {
            val entry = exportEntry.toEntry(ownerId)
            if (!entries.restore(entry)) {
                skipped++
                continue
            }
            restored++
            for (exportAttachment in exportEntry.attachments) {
                val path = exportAttachment.mediaPath ?: continue
                val bytes = files[path] ?: continue
                media.stage(
                    entryId = entry.id,
                    attachmentId = exportAttachment.id,
                    path = null,
                    bytes = bytes,
                    size = bytes.size.toLong(),
                )
                mediaRestored++
            }
        }
        return ImportResult(restored, skipped, mediaRestored)
    }
}
