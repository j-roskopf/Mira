package com.joetr.mira.data.archive

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.joetr.mira.data.entries.EntryRepository
import com.joetr.mira.data.entries.media.MediaRepository
import com.joetr.mira.data.entries.media.RetentionPolicy
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.*
import com.joetr.mira.network.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExportServiceTest {

    @Test
    fun exportThenImportRestoresEntriesAndMediaOnAFreshInstall() = runTest {
        val source = Fixture()
        val destination = Fixture()
        try {
            source.initialize()
            destination.initialize()
            source.media.stage("draft", "video", null, byteArrayOf(1, 2, 3), 3)
            val entry = source.entries.create(
                "owner-a",
                body = "A day worth keeping.",
                attachments = listOf(Attachment("video", AttachmentKind.VIDEO, "", mime = "video/mp4", bytes = 3)),
            )

            val bundle = source.service.export("owner-a")
            val result = destination.service.import("owner-b", bundle)

            assertEquals(1, result.entriesRestored)
            assertEquals(0, result.entriesSkipped)
            assertEquals(1, result.mediaRestored)

            val restored = destination.entries.entry(entry.id)
            assertNotNull(restored)
            assertEquals("owner-b", restored.ownerId)
            assertEquals("A day worth keeping.", restored.body)
            assertEquals(1, restored.attachments.size)
            assertEquals(
                byteArrayOf(1, 2, 3).toList(),
                destination.media.bytesFor(restored.attachments.single())?.toList(),
            )
        } finally {
            source.close()
            destination.close()
        }
    }

    @Test
    fun importDoesNotClobberANewerLocalEdit() = runTest {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val entry = fixture.entries.create("owner-a", body = "Original.")
            val bundle = fixture.service.export("owner-a")

            fixture.clock.now += 1_000
            fixture.entries.save(entry.copy(body = "Edited after the backup."))

            val result = fixture.service.import("owner-a", bundle)

            assertEquals(0, result.entriesRestored)
            assertEquals(1, result.entriesSkipped)
            assertEquals("Edited after the backup.", fixture.entries.entry(entry.id)?.body)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun importRejectsSomethingThatIsNotAMiraExport() = runTest {
        val fixture = Fixture()
        try {
            fixture.initialize()
            assertFailsWith<ImportFailedException> {
                fixture.service.import("owner-a", "not an export file".encodeToByteArray())
            }
        } finally {
            fixture.close()
        }
    }

    private class FakeClock(var now: Long) : ClockZone {
        override fun nowMillis() = now
        override fun zone() = TimeZone.UTC
    }

    private class Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = MiraDatabase(driver)
        val client = HttpClient(MockEngine { respond("{}") })
        val clock = FakeClock(1_800_000_000_000L)
        val entries = EntryRepository(db, clock)
        val firebase = MiraFirebase(client, object : FirebaseSessionStore {
            override suspend fun load(): FirebaseSession? = null
            override suspend fun save(session: FirebaseSession?) {}
        })
        val media = MediaRepository(db, firebase, entries, RetentionPolicy.KeepAll)
        val service = ExportService(entries, media, "test-version")

        suspend fun initialize() {
            MiraDatabase.Schema.create(driver).await()
        }

        fun close() {
            client.close()
            driver.close()
        }
    }
}
