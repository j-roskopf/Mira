package com.joetr.mira.data.settings

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.joetr.mira.db.MiraDatabase
import com.joetr.mira.domain.CaptureQuality
import com.joetr.mira.domain.ThemeMode
import com.joetr.mira.domain.UserSettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
public class DefaultUserSettingsRepository(
    private val database: MiraDatabase,
) : UserSettingsRepository {

    private val queries get() = database.entriesQueries

    private val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    private val accent = MutableStateFlow(DEFAULT_ACCENT)
    private val mediaCacheCap = MutableStateFlow(DEFAULT_CACHE_CAP_BYTES)
    private val videoUnmeteredOnly = MutableStateFlow(true)
    private val includeMediaInArchive = MutableStateFlow(false)
    private val videoQuality = MutableStateFlow(CaptureQuality.MEDIUM)
    private val photoQuality = MutableStateFlow(CaptureQuality.MEDIUM)

    private var loaded = false

    /** Reads stored values once, so the first composition does not flash defaults. */
    public suspend fun load() {
        if (loaded) return
        loaded = true
        withContext(Dispatchers.Default) {
            themeMode.value = ThemeMode.fromStored(read(KEY_THEME))
            accent.value = read(KEY_ACCENT) ?: DEFAULT_ACCENT
            mediaCacheCap.value = read(KEY_CACHE_CAP)?.toLongOrNull() ?: DEFAULT_CACHE_CAP_BYTES
            videoUnmeteredOnly.value = read(KEY_VIDEO_UNMETERED)?.toBooleanStrictOrNull() ?: true
            includeMediaInArchive.value =
                read(KEY_ARCHIVE_MEDIA)?.toBooleanStrictOrNull() ?: false
            videoQuality.value = read(KEY_VIDEO_QUALITY).toCaptureQuality(CaptureQuality.MEDIUM)
            photoQuality.value = read(KEY_PHOTO_QUALITY).toCaptureQuality(CaptureQuality.MEDIUM)
        }
    }

    override fun observeThemeMode(): Flow<ThemeMode> = themeMode.asStateFlow()

    override suspend fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
        write(KEY_THEME, mode.name)
    }

    override fun observeAccent(): Flow<String> = accent.asStateFlow()

    override suspend fun setAccent(name: String) {
        accent.value = name
        write(KEY_ACCENT, name)
    }

    override fun observeMediaCacheCapBytes(): Flow<Long> = mediaCacheCap.asStateFlow()

    override suspend fun setMediaCacheCapBytes(bytes: Long) {
        mediaCacheCap.value = bytes
        write(KEY_CACHE_CAP, bytes.toString())
    }

    override fun observeVideoUploadOnUnmeteredOnly(): Flow<Boolean> =
        videoUnmeteredOnly.asStateFlow()

    override suspend fun setVideoUploadOnUnmeteredOnly(enabled: Boolean) {
        videoUnmeteredOnly.value = enabled
        write(KEY_VIDEO_UNMETERED, enabled.toString())
    }

    override fun observeIncludeMediaInArchive(): Flow<Boolean> =
        includeMediaInArchive.asStateFlow()

    override suspend fun setIncludeMediaInArchive(enabled: Boolean) {
        includeMediaInArchive.value = enabled
        write(KEY_ARCHIVE_MEDIA, enabled.toString())
    }

    override fun observeVideoQuality(): Flow<CaptureQuality> = videoQuality.asStateFlow()

    override suspend fun setVideoQuality(quality: CaptureQuality) {
        videoQuality.value = quality
        write(KEY_VIDEO_QUALITY, quality.name)
    }

    override fun observePhotoQuality(): Flow<CaptureQuality> = photoQuality.asStateFlow()

    override suspend fun setPhotoQuality(quality: CaptureQuality) {
        photoQuality.value = quality
        write(KEY_PHOTO_QUALITY, quality.name)
    }

    private fun String?.toCaptureQuality(fallback: CaptureQuality): CaptureQuality =
        CaptureQuality.entries.firstOrNull { it.name == this } ?: fallback

    private suspend fun read(key: String): String? = withContext(Dispatchers.Default) {
        queries.getSetting(key).awaitAsOneOrNull()?.takeIf { it.isNotBlank() }
    }

    private suspend fun write(key: String, value: String) {
        withContext(Dispatchers.Default) { queries.putSetting(key, value) }
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_ACCENT = "accent"
        const val KEY_CACHE_CAP = "media_cache_cap_bytes"
        const val KEY_VIDEO_UNMETERED = "video_upload_unmetered_only"
        const val KEY_ARCHIVE_MEDIA = "archive_include_media"
        const val KEY_VIDEO_QUALITY = "capture_video_quality"
        const val KEY_PHOTO_QUALITY = "capture_photo_quality"

        const val DEFAULT_ACCENT = "EMBER"

        /** 5 GB. Mobile only — desktop keeps everything, web keeps nothing. */
        const val DEFAULT_CACHE_CAP_BYTES = 5L * 1024 * 1024 * 1024
    }
}
