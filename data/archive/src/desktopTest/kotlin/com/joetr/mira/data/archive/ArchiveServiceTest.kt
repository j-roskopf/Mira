package com.joetr.mira.data.archive

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.data.entries.EntryRepository
import com.joetr.mira.data.entries.media.MediaRepository
import com.joetr.mira.data.entries.media.RetentionPolicy
import com.joetr.mira.domain.*
import com.joetr.mira.network.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArchiveServiceTest {
    @Test
    fun mediaBackfillsAfterTextBackupAndIsNotUploadedTwice() = runTest {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val entry = fixture.record()
            assertEquals(entry.id, fixture.db.entriesQueries.selectBlob("video").awaitAsOne().entry_id)
            assertIs<ArchiveResult.Pushed>(fixture.service.pushNow("owner", false))
            assertEquals(0, fixture.binaryUploads)
            assertFalse(fixture.notes.last().contains("Watch video"))
            assertIs<ArchiveResult.Pushed>(fixture.service.pushNow("owner", true))
            assertEquals(1, fixture.binaryUploads)
            assertTrue(fixture.notes.last().contains("[Watch video 1](../../../media/"))
            assertEquals(ArchiveResult.NothingToDo, fixture.service.pushNow("owner", true))
            assertEquals(1, fixture.binaryUploads)
        } finally { fixture.close() }
    }

    @Test
    fun existingBackupsGetLinksWithoutReuploadingMedia() = runTest {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val entry = fixture.record()
            assertIs<ArchiveResult.Pushed>(fixture.service.pushNow("owner", true))
            // Simulate a backup produced before notes contained media links.
            fixture.db.entriesQueries.putSetting(
                "github_note_links:owner:me/journal:main:${entry.archivePath()}", "",
            )
            fixture.notes.clear()
            assertIs<ArchiveResult.Pushed>(fixture.service.pushNow("owner", false))
            assertTrue(fixture.notes.single().contains("[Watch video 1](../../../media/"))
            assertEquals(1, fixture.binaryUploads)
            assertEquals(ArchiveResult.NothingToDo, fixture.service.pushNow("owner", false))
        } finally { fixture.close() }
    }

    @Test
    fun unavailableMediaReportsFailureAndCanBeRetried() = runTest {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.record(stage = false)
            assertIs<ArchiveResult.Failed>(fixture.service.pushNow("owner", true))
            assertTrue(fixture.service.status.value.errorMessage!!.contains("Could not read"))
            assertFalse(fixture.service.status.value.pushing)
            fixture.media.stage("draft", "video", null, byteArrayOf(1, 2, 3), 3)
            assertIs<ArchiveResult.Pushed>(fixture.service.pushNow("owner", true))
            assertNull(fixture.service.status.value.errorMessage)
        } finally { fixture.close() }
    }

    @Test
    fun missingOldRecordingDoesNotBlockAvailableMedia() = runTest {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.record(stage = false)
            fixture.media.stage("draft", "available", null, byteArrayOf(4, 5), 2)
            fixture.entries.create("owner", attachments = listOf(
                Attachment("available", AttachmentKind.VIDEO, "", mime = "video/mp4", bytes = 2),
            ))
            assertIs<ArchiveResult.Failed>(fixture.service.pushNow("owner", true))
            assertEquals(1, fixture.binaryUploads)
            assertTrue(fixture.service.status.value.errorMessage!!.contains("Backup incomplete"))
            assertIs<ArchiveResult.Failed>(fixture.service.pushNow("owner", true))
            assertEquals(1, fixture.binaryUploads)
        } finally { fixture.close() }
    }

    @Test
    fun automaticBackupHonorsMediaSetting() = runTest {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.record()
            fixture.db.entriesQueries.putSetting("archive_include_media", "true")
            val service = ArchiveService(fixture.db, fixture.entries, fixture.media, fixture.client, this)
            service.load()
            service.rememberOwner("owner")
            service.onLocalWrite()
            advanceTimeBy(300_001)
            testScheduler.advanceUntilIdle()
            // Database reads use Dispatchers.Default, so wait for the actual push to finish.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) {
                    while (service.status.value.lastPushedAt == null) kotlinx.coroutines.delay(10)
                }
            }
            assertEquals(1, fixture.binaryUploads)
        } finally { fixture.close() }
    }

    @Test
    fun sharedTargetAppliesWithoutAuthorizingThisDevice() = runTest {
        withSharedConfig(
            """{"fields":{"enabled":{"booleanValue":true},"repoSlug":{"stringValue":"me/journal"},"branch":{"stringValue":"archive"}}}""",
        ) { db, service, _ ->
            service.refreshSharedConfig()
            assertEquals("me/journal", service.status.value.repoSlug)
            assertFalse(service.status.value.connected)
            assertEquals(
                "archive",
                db.entriesQueries.getSetting("github_archive_branch").awaitAsOneOrNull(),
            )
        }
    }

    @Test
    fun disabledSharedTargetStopsArchivingOnThisDevice() = runTest {
        withSharedConfig(
            """{"fields":{"enabled":{"booleanValue":false},"branch":{"stringValue":"main"}}}""",
            seedLocalTarget = true,
        ) { db, service, _ ->
            service.refreshSharedConfig()
            assertFalse(service.status.value.connected)
            assertNull(service.status.value.repoSlug)
            assertTrue(
                db.entriesQueries.getSetting("github_archive_token").awaitAsOneOrNull().isNullOrBlank(),
            )
        }
    }

    private suspend fun withSharedConfig(
        response: String,
        seedLocalTarget: Boolean = false,
        block: suspend (MiraDatabase, ArchiveService, MiraFirebase) -> Unit,
    ) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = MiraDatabase(driver)
        MiraDatabase.Schema.create(driver).await()
        if (seedLocalTarget) {
            db.entriesQueries.putSetting("github_archive_token", "token")
            db.entriesQueries.putSetting("github_archive_repo", "me/journal")
            db.entriesQueries.putSetting("github_archive_branch", "main")
        }
        val session = FirebaseSession(
            localId = "owner",
            idToken = "id-token",
            refreshToken = "refresh-token",
            expiresAtEpochMs = Long.MAX_VALUE,
            isAnonymous = false,
        )
        val client = HttpClient(MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/users/owner/meta/archive"))
            respond(response)
        })
        try {
            val firebase = MiraFirebase(client, object : FirebaseSessionStore {
                override suspend fun load(): FirebaseSession = session
                override suspend fun save(session: FirebaseSession?) {}
            })
            val clock = object : ClockZone {
                override fun nowMillis() = 0L
                override fun zone() = TimeZone.UTC
            }
            val entries = EntryRepository(db, clock)
            val media = MediaRepository(db, firebase, entries, RetentionPolicy.KeepAll)
            val service = ArchiveService(db, entries, media, client, firebase = firebase)
            block(db, service, firebase)
        } finally {
            client.close()
            driver.close()
        }
    }

    private class Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = MiraDatabase(driver)
        var binaryUploads = 0
        val notes = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("/git/ref/heads/") -> respond("""{"object":{"sha":"head"}}""")
                path.endsWith("/git/commits/head") -> respond("""{"tree":{"sha":"tree"}}""")
                else -> {
                    if (path.endsWith("/git/blobs")) {
                        val blob = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        if (blob["encoding"]!!.jsonPrimitive.content == "base64") binaryUploads++
                        else notes.add(blob["content"]!!.jsonPrimitive.content)
                    }
                    respond("""{"sha":"new"}""")
                }
            }
        })
        val clock = object : ClockZone {
            override fun nowMillis() = 1_800_000_000_000L
            override fun zone() = TimeZone.UTC
        }
        val entries = EntryRepository(db, clock)
        val firebase = MiraFirebase(client, object : FirebaseSessionStore {
            override suspend fun load(): FirebaseSession? = null
            override suspend fun save(session: FirebaseSession?) {}
        })
        val media = MediaRepository(db, firebase, entries, RetentionPolicy.KeepAll)
        val service = ArchiveService(db, entries, media, client)
        suspend fun initialize() {
            MiraDatabase.Schema.create(driver).await()
            service.connect("token", ArchiveTarget("me", "journal", "main"))
        }
        suspend fun record(stage: Boolean = true): Entry {
            if (stage) media.stage("draft", "video", null, byteArrayOf(1, 2, 3), 3)
            return entries.create("owner", attachments = listOf(
                Attachment("video", AttachmentKind.VIDEO, "", mime = "video/mp4", bytes = 3),
            ))
        }
        fun close() { client.close(); driver.close() }
    }
}
