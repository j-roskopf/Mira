package com.joetr.mira.updates

import com.joetr.mira.platform.MiraBuildInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object GitHubReleaseChecker {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdate(
        httpClient: HttpClient,
        platform: UpdatePlatform,
        currentVersionName: String = MiraBuildInfo.versionName,
        githubOwner: String = MiraBuildInfo.githubOwner,
        githubRepo: String = MiraBuildInfo.githubRepo,
    ): AvailableUpdate? {
        val currentVersion = SemanticVersion.parse(currentVersionName) ?: return null
        val url = "https://api.github.com/repos/$githubOwner/$githubRepo/releases/latest"
        val response = httpClient.get(url) {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "Mira/$currentVersionName")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (response.status.value != 200) {
            error("GitHub release check failed: HTTP ${response.status.value}")
        }

        val release = json.decodeFromString<GitHubReleaseResponse>(response.bodyAsText())
        if (release.draft || release.prerelease) return null

        val latestVersion = SemanticVersion.parse(release.tagName) ?: return null
        if (latestVersion <= currentVersion) return null

        val assets = release.assets.map { asset ->
            GitHubAssetDto(
                name = asset.name,
                browserDownloadUrl = asset.browserDownloadUrl,
                size = asset.size,
                digest = asset.digest,
                state = asset.state,
            )
        }

        return AvailableUpdate(
            versionName = latestVersion.toString(),
            releaseName = release.name,
            releaseNotes = release.body,
            releasePageUrl = release.htmlUrl,
            asset = selectUpdateAsset(platform, assets),
        )
    }
}

@Serializable
private data class GitHubReleaseResponse(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAssetResponse> = emptyList(),
)

@Serializable
private data class GitHubAssetResponse(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L,
    val digest: String? = null,
    val state: String? = null,
)
