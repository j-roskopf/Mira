package com.joetr.mira.data.entries.media

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.joetr.mira.data.entries.EntryRepository
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.ClockZone
import com.joetr.mira.network.FirebaseSession
import com.joetr.mira.network.FirebaseSessionStore
import com.joetr.mira.network.MiraFirebase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingUploadQueueTest {
    @Test
    fun removedAttachmentStopsCountingAsWaitingToUpload() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            MiraDatabase.Schema.create(driver).await()
            val db = MiraDatabase(driver)
            val entries = EntryRepository(
                db,
                object : ClockZone {
                    override fun nowMillis() = 1_800_000_000_000L
                    override fun zone() = TimeZone.UTC
                },
            )
            val client = HttpClient(MockEngine { respond("") })
            try {
                val firebase = MiraFirebase(
                    client,
                    object : FirebaseSessionStore {
                        override suspend fun load(): FirebaseSession? = null
                        override suspend fun save(session: FirebaseSession?) {}
                    },
                )
                val media = MediaRepository(db, firebase, entries, RetentionPolicy.KeepAll)
                val attachment = Attachment("audio", AttachmentKind.AUDIO, "", mime = "audio/mp4", bytes = 3)
                media.stage(entryId = "draft", attachmentId = "audio", path = null, bytes = byteArrayOf(1, 2, 3), size = 3)
                val entry = entries.create("owner", attachments = listOf(attachment))
                assertEquals(1, media.pendingUploadCount())

                entries.save(entry.copy(attachments = emptyList()))
                assertEquals(0, media.pendingUploadCount())

                media.pruneUnreferencedStaged(entry.id, emptyList())
                assertEquals(0, db.entriesQueries.selectBlobsForEntry(entry.id).awaitAsList().size)
            } finally {
                client.close()
            }
        } finally {
            driver.close()
        }
    }
}
