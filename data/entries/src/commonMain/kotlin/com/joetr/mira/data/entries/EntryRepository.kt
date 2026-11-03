package com.joetr.mira.data.entries

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.ClockZone
import com.joetr.mira.domain.DayActivity
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.domain.OutboxKind
import com.joetr.mira.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local store for entries. Every write marks the row pending and enqueues it; the sync service
 * drains the outbox separately, so the UI never waits on the network.
 */
public class EntryRepository(
    private val database: MiraDatabase,
    private val clock: ClockZone,
) {
    private val queries get() = database.entriesQueries

    public suspend fun entriesForDate(ownerId: String, date: LocalDateKey): List<Entry> =
        io { queries.selectEntriesForDate(ownerId, date.value).awaitAsList().map { it.toEntry() } }

    public suspend fun entry(id: String): Entry? =
        io { queries.selectEntry(id).awaitAsOneOrNull()?.toEntry() }

    public suspend fun timeline(ownerId: String, limit: Int, offset: Int): List<Entry> =
        io {
            queries.selectTimeline(ownerId, limit.toLong(), offset.toLong())
                .awaitAsList()
                .map { it.toEntry() }
        }

    public suspend fun entriesInRange(
        ownerId: String,
        from: LocalDateKey,
        to: LocalDateKey,
    ): List<Entry> = io {
        queries.selectEntriesInRange(ownerId, from.value, to.value).awaitAsList().map { it.toEntry() }
    }

    /** Entry counts per day for [month], which is what colours the calendar heatmap. */
    public suspend fun activityForMonth(ownerId: String, month: MonthKey): Map<LocalDateKey, Int> =
        io {
            queries.countsByDate(ownerId, month.firstDay.value, month.lastDay.value)
                .awaitAsList()
                .associate { LocalDateKey(it.local_date) to it.entry_count.toInt() }
        }

    public suspend fun activityForYear(ownerId: String, year: Int): List<DayActivity> = io {
        val from = LocalDateKey("$year-01-01")
        val to = LocalDateKey("$year-12-31")
        queries.countsByDate(ownerId, from.value, to.value)
            .awaitAsList()
            .map { DayActivity(LocalDateKey(it.local_date), it.entry_count.toInt()) }
    }

    /**
     * Local text search.
     *
     * Deliberately a `LIKE` over body and tags rather than an index: a journal is thousands of
     * rows, not millions. Audio and video content is not searchable — there is no transcription —
     * so media entries are found by date, tag, and any caption typed alongside them.
     */
    public suspend fun search(
        ownerId: String,
        query: String,
        filters: SearchFilters = SearchFilters(),
        limit: Int = 200,
    ): List<Entry> = io {
        val rows = if (query.isBlank()) {
            queries.selectTimeline(ownerId, limit.toLong(), 0L).awaitAsList()
        } else {
            queries.searchEntries(ownerId, query, limit.toLong()).awaitAsList()
        }
        rows.map { it.toEntry() }.filter { filters.matches(it) }
    }

    /**
     * Tags used most recently, newest first, de-duplicated case-insensitively.
     *
     * Read from the last hundred touched entries rather than a separate tag table: the journal is
     * small, and a derived list needs no migration or sync rules of its own.
     */
    public suspend fun recentTags(ownerId: String, limit: Int = 20): List<String> = io {
        queries.selectRecentTags(ownerId).awaitAsList()
            .flatMap { decodeTags(it) }
            .distinctBy { it.lowercase() }
            .take(limit)
    }

    public suspend fun earliestDate(ownerId: String): LocalDateKey? = io {
        queries.selectEarliestEntryDate(ownerId).awaitAsOneOrNull()?.MIN?.let { LocalDateKey(it) }
    }

    /** Every live entry for [ownerId]. Used when the whole journal is being deleted. */
    public suspend fun entriesForOwner(ownerId: String): List<Entry> = io {
        queries.selectAllEntries(ownerId).awaitAsList().map { it.toEntry() }
    }

    /** Creates a new entry on [date], defaulting to today. */
    public suspend fun create(
        ownerId: String,
        body: String = "",
        date: LocalDateKey = clock.today(),
        attachments: List<Attachment> = emptyList(),
        tags: List<String> = emptyList(),
    ): Entry {
        val now = clock.nowMillis()
        val entry = Entry(
            id = newEntryId(now),
            ownerId = ownerId,
            localDate = date,
            createdAt = now,
            createdTz = clock.zoneId(),
            updatedAt = now,
            body = body,
            attachments = attachments,
            tags = tags,
        )
        save(entry)
        return entry
    }

    /** Writes [entry], marks it pending, and enqueues an upsert. */
    public suspend fun save(entry: Entry) {
        val now = clock.nowMillis()
        io {
            queries.upsertEntry(
                id = entry.id,
                owner_id = entry.ownerId,
                local_date = entry.localDate.value,
                created_at = entry.createdAt,
                created_tz = entry.createdTz,
                updated_at = now,
                body = entry.body,
                attachments = encodeAttachments(entry.attachments),
                tags = encodeTags(entry.tags),
                deleted = if (entry.deleted) 1L else 0L,
                pending_sync = 1L,
            )
            entry.attachments.forEach { queries.assignBlobToEntry(entry.id, it.id) }
            queries.enqueue(entry.id, OutboxKind.UPSERT.wire, now)
        }
    }

    /**
     * Writes [entry] only if it's new locally or newer than what's stored — the same
     * last-write-wins rule sync uses for a remote row, applied to an imported one. This is what
     * keeps importing an old backup from clobbering edits made since it was taken.
     *
     * Unlike [save], the imported [Entry.updatedAt] is kept rather than stamped with "now": a
     * restored entry must carry its original age so the next real sync can compare it correctly.
     */
    public suspend fun restore(entry: Entry): Boolean = io {
        val existing = queries.selectEntry(entry.id).awaitAsOneOrNull()
        if (existing != null && existing.updated_at >= entry.updatedAt) return@io false
        queries.upsertEntry(
            id = entry.id,
            owner_id = entry.ownerId,
            local_date = entry.localDate.value,
            created_at = entry.createdAt,
            created_tz = entry.createdTz,
            updated_at = entry.updatedAt,
            body = entry.body,
            attachments = encodeAttachments(entry.attachments),
            tags = encodeTags(entry.tags),
            deleted = if (entry.deleted) 1L else 0L,
            pending_sync = 1L,
        )
        entry.attachments.forEach { queries.assignBlobToEntry(entry.id, it.id) }
        queries.enqueue(entry.id, OutboxKind.UPSERT.wire, currentTimeMillis())
        true
    }

    /** Moves an entry to another day. A plain edit — you will want to write up yesterday tonight. */
    public suspend fun move(entryId: String, date: LocalDateKey) {
        val now = clock.nowMillis()
        io {
            queries.moveEntryToDate(date.value, now, entryId)
            queries.enqueue(entryId, OutboxKind.UPSERT.wire, now)
        }
    }

    /**
     * Tombstones an entry.
     *
     * Storage objects are deleted separately by the caller, immediately where possible. With no
     * Cloud Functions nothing server-side reclaims them, so Settings carries an orphan sweep for
     * the case where the client was offline when this ran.
     */
    public suspend fun delete(entryId: String) {
        val now = clock.nowMillis()
        io {
            queries.markEntryDeleted(now, entryId)
            queries.enqueue(entryId, OutboxKind.DELETE.wire, now)
        }
    }

    public suspend fun pendingCount(): Int = io { queries.selectOutbox().awaitAsList().size }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

    private fun newEntryId(now: Long): String {
        // Time-ordered so entry ids sort chronologically in the archive and in Firestore listings.
        val time = now.toString(16).padStart(12, '0')
        val random = buildString {
            repeat(16) { append(HEX[(0 until 16).random()]) }
        }
        return "$time-$random"
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}

/** Structured filters applied alongside the text pass. */
public data class SearchFilters(
    val from: LocalDateKey? = null,
    val to: LocalDateKey? = null,
    val hasAudio: Boolean = false,
    val hasVideo: Boolean = false,
    val tag: String? = null,
) {
    public fun matches(entry: Entry): Boolean {
        if (from != null && entry.localDate < from) return false
        if (to != null && entry.localDate > to) return false
        if (hasAudio && !entry.hasAudio) return false
        if (hasVideo && !entry.hasVideo) return false
        if (tag != null && entry.tags.none { it.equals(tag, ignoreCase = true) }) return false
        return true
    }

    public val isActive: Boolean
        get() = from != null || to != null || hasAudio || hasVideo || tag != null
}
