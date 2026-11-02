package com.joetr.mira.updates

import kotlinx.coroutines.flow.StateFlow

public interface AppUpdateService {
    public val state: StateFlow<AppUpdateState>
    public val pendingInstallConfirmation: StateFlow<AvailableUpdate?>

    public suspend fun checkForUpdates(onFailure: (Throwable) -> Unit = {})
    public suspend fun installAvailableUpdate(onMessage: (String) -> Unit = {})
    public fun respondToInstallConfirmation(install: Boolean)
}

public sealed interface AppUpdateState {
    public data object Idle : AppUpdateState
    public data object Checking : AppUpdateState
    public data object Current : AppUpdateState
    public data class Available(val update: AvailableUpdate) : AppUpdateState
    public data class Installing(
        val update: AvailableUpdate,
        val message: String,
        val progress: Float? = null,
    ) : AppUpdateState
    public data class Failed(
        val message: String,
        val lastKnownUpdate: AvailableUpdate? = null,
    ) : AppUpdateState
}

public data class AvailableUpdate(
    val versionName: String,
    val releaseName: String?,
    val releaseNotes: String?,
    val releasePageUrl: String,
    val asset: ReleaseAsset?,
)

public data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256Digest: String?,
)

/** The outcome of an install attempt, used to keep the shared UI state accurate. */
public sealed interface UpdateInstallResult {
    public val message: String

    /** The platform installer was handed off; the app may close or relaunch. */
    public data class Started(override val message: String) : UpdateInstallResult

    /** The user was sent to the release page because this platform cannot self-install. */
    public data class OpenedReleasePage(override val message: String) : UpdateInstallResult

    /** The user needs to grant a permission or finish an installer step before retrying. */
    public data class RequiresUserAction(override val message: String) : UpdateInstallResult
}

public enum class UpdatePlatform {
    Android,
    Ios,
    MacOs,
    Windows,
    LinuxDeb,
    LinuxFlatpak,
    Web,
    Other,
}
