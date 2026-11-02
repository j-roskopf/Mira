package com.joetr.mira.di

import com.joetr.mira.data.archive.ArchiveService
import com.joetr.mira.data.archive.ExportService
import com.joetr.mira.data.archive.GitHubDeviceFlow
import com.joetr.mira.data.auth.DefaultSessionRepository
import com.joetr.mira.data.auth.createFirebaseSessionStore
import com.joetr.mira.data.entries.DefaultEntryStore
import com.joetr.mira.data.entries.DefaultSyncCoordinator
import com.joetr.mira.data.entries.EntryRepository
import com.joetr.mira.data.entries.media.MediaRepository
import com.joetr.mira.data.entries.media.RetentionPolicy
import com.joetr.mira.data.entries.sync.EntrySyncService
import com.joetr.mira.data.settings.DefaultUserSettingsRepository
import com.joetr.mira.database.MiraDataLayer
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.AttachmentState
import com.joetr.mira.domain.SystemClockZone
import com.joetr.mira.feature.auth.AuthViewModel
import com.joetr.mira.media.CaptureKind
import com.joetr.mira.media.CaptureResult
import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.network.createMiraHttpClient
import com.joetr.mira.platform.AppLifecycleObserver
import com.joetr.mira.platform.MiraBuildInfo
import com.joetr.mira.platform.MiraConfig
import com.joetr.mira.platform.isUnmeteredNetwork
import com.joetr.mira.platform.currentTimeMillis
import com.joetr.mira.updates.createAppUpdateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Object graph for the app.
 *
 * Startup order matters: the persisted Firebase session is restored before anything asks who the
 * owner is, and sync runs after — otherwise the first pull happens as the anonymous device user and
 * pulls nothing.
 */
public class AppGraph(
    dataLayer: MiraDataLayer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = createMiraHttpClient()
    private val clock = SystemClockZone()

    private val firebase = MiraFirebase.create(
        httpClient = httpClient,
        sessionStore = createFirebaseSessionStore(dataLayer.database),
    )

    public val entries: EntryRepository = EntryRepository(dataLayer.database, clock)

    private val syncService = EntrySyncService(dataLayer.database, firebase, scope)
    private val syncCoordinator = DefaultSyncCoordinator(syncService)
    private val entryStore = DefaultEntryStore(dataLayer.database)

    public val media: MediaRepository = MediaRepository(
        database = dataLayer.database,
        firebase = firebase,
        entries = entries,
        policy = defaultRetentionPolicy(),
    )

    public val archive: ArchiveService = ArchiveService(
        database = dataLayer.database,
        entries = entries,
        media = media,
        httpClient = httpClient,
        scope = scope,
    )

    public val exportService: ExportService = ExportService(
        entries = entries,
        media = media,
        appVersion = MiraBuildInfo.versionName,
    )

    public val settings: DefaultUserSettingsRepository =
        DefaultUserSettingsRepository(dataLayer.database)

    private val sessionRepository = DefaultSessionRepository(
        dataLayer.database,
        entryStore,
        firebase,
        syncCoordinator,
    )

    public val session: DefaultSessionRepository get() = sessionRepository
    public val sync: EntrySyncService get() = syncService
    public val updates = createAppUpdateService(scope, httpClient)

    public val authViewModel: AuthViewModel = AuthViewModel(sessionRepository)

    private val startupJob = scope.launch {
        settings.load()
        archive.load()
        firebase.preloadPersistedSession()
        val signedInViaOAuth = sessionRepository.resumePendingWebOAuth()
        sessionRepository.ensureSession()
        if (signedInViaOAuth) {
            syncCoordinator.syncAfterSignIn()
        } else {
            syncService.syncNow()
        }
        drainMediaUploads()
        updates.checkForUpdates()
    }

    /**
     * Uploads anything still staged locally.
     *
     * Audio goes immediately — a two-minute note is about a megabyte. Video waits for an unmetered
     * connection unless the user turned that off, so an entry can reach another device before its
     * video does; that is a state the UI renders rather than an error.
     */
    public suspend fun drainMediaUploads() {
        val owner = sessionRepository.currentOwnerId() ?: return
        val unmeteredOnly = settings.observeVideoUploadOnUnmeteredOnly().first()
        media.uploadPending(owner, includeVideo = !unmeteredOnly || isUnmeteredNetwork())
    }

    init {
        AppLifecycleObserver.onForeground = {
            scope.launch {
                syncService.syncIfStale()
                // Catches video that was deferred while on a metered connection.
                drainMediaUploads()
            }
        }
    }

    internal suspend fun awaitStartup() {
        startupJob.join()
    }

    /** The archive's HTTP client, shared with the rest of the app. */
    public fun httpClientForArchive(): io.ktor.client.HttpClient = httpClient

    /**
     * Device flow for connecting the GitHub archive, or null when no client id was configured.
     *
     * There is no client secret by design — device flow needs only the id, which is what keeps the
     * archive feature free of any server component.
     */
    public fun gitHubDeviceFlow(): GitHubDeviceFlow? {
        val clientId = MiraConfig.GITHUB_OAUTH_CLIENT_ID
        if (clientId.isBlank()) return null
        return GitHubDeviceFlow(httpClient, clientId)
    }

    /**
     * Turns a finished recording into an attachment and stages its bytes for upload.
     *
     * The attachment is created in [AttachmentState.LOCAL] and only becomes UPLOADED once Storage
     * confirms it, which is what lets an entry sync to another device ahead of its media.
     */
    public suspend fun attachFrom(result: CaptureResult, entryId: String?): Attachment {
        val attachmentId = "${currentTimeMillis()}-${result.kind.name.lowercase()}"
        val attachment = Attachment(
            id = attachmentId,
            kind = when (result.kind) {
                CaptureKind.VIDEO -> AttachmentKind.VIDEO
                CaptureKind.PHOTO -> AttachmentKind.IMAGE
                CaptureKind.AUDIO -> AttachmentKind.AUDIO
            },
            storagePath = "",
            mime = result.mime,
            bytes = result.sizeBytes,
            durationMs = result.durationMs,
            state = AttachmentState.LOCAL,
        )
        media.stage(
            entryId = entryId ?: attachmentId,
            attachmentId = attachmentId,
            path = result.path,
            bytes = result.bytes,
            size = result.sizeBytes,
        )
        return attachment
    }

    /**
     * Permanently deletes every entry and attachment for [ownerId].
     *
     * Entries are tombstoned and queued so other devices delete them on the next sync; media is
     * removed from Storage eagerly and from the local cache. Account, preferences and any connected
     * GitHub archive are left alone — the archive is a separate copy the user chose to keep.
     */
    public suspend fun deleteAllData(ownerId: String) {
        val journal = entries.entriesForOwner(ownerId)
        for (entry in journal) {
            media.deleteMediaFor(entry.id, entry.attachments)
            entries.delete(entry.id)
        }
        media.clearLocalMedia()
        syncService.syncNow(forcePull = true)
    }

    /** Sync a saved entry in the app scope so leaving the editor cannot cancel the upload. */
    public fun onEntrySaved(ownerId: String) {
        scope.launch { syncService.syncNow() }
        scope.launch {
            if (archive.status.value.connected) {
                archive.pushNow(ownerId, settings.observeIncludeMediaInArchive().first())
            }
        }
        scope.launch { drainMediaUploads() }
    }

    /** Syncs and backs up everything an import just restored, in the app scope. */
    public fun onImportFinished(ownerId: String) {
        scope.launch { syncService.syncNow() }
        scope.launch {
            if (archive.status.value.connected) {
                archive.pushNow(ownerId, settings.observeIncludeMediaInArchive().first())
            }
        }
        scope.launch { drainMediaUploads() }
    }

    /** Called after any local write: entry sync is eager, the archive push is debounced. */
    public fun onLocalWrite() {
        syncService.onLocalWrite()
        archive.onLocalWrite()
        scope.launch { drainMediaUploads() }
    }
}

/**
 * How much media this platform keeps locally once it is safely in Storage.
 *
 * Desktop keeps a complete offline archive; mobile keeps an LRU-capped cache; web keeps nothing,
 * because browser storage quota is a fraction of free disk and is evicted without warning.
 */
internal expect fun defaultRetentionPolicy(): RetentionPolicy

public suspend fun createMiraAppGraph(): AppGraph {
    val dataLayer = com.joetr.mira.database.createMiraDataLayer()
    val graph = AppGraph(dataLayer)
    graph.awaitStartup()
    return graph
}
