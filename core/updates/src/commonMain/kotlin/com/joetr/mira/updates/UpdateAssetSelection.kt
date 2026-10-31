package com.joetr.mira.updates

internal data class GitHubAssetDto(
    val name: String,
    val browserDownloadUrl: String,
    val size: Long = 0L,
    val digest: String? = null,
    val state: String? = null,
) {
    fun toReleaseAsset(): ReleaseAsset =
        ReleaseAsset(
            name = name,
            downloadUrl = browserDownloadUrl,
            sizeBytes = size,
            sha256Digest = digest?.trim()?.lowercase()?.removePrefix("sha256:"),
        )
}

internal fun selectUpdateAsset(
    platform: UpdatePlatform,
    assets: List<GitHubAssetDto>,
): ReleaseAsset? {
    val uploadedAssets = assets.filter { asset ->
        asset.browserDownloadUrl.isNotBlank() && asset.state?.equals("uploaded", ignoreCase = true) != false
    }
    fun firstWithExtension(vararg extensions: String): ReleaseAsset? =
        extensions.firstNotNullOfOrNull { extension ->
            uploadedAssets.firstOrNull { asset -> asset.name.endsWith(extension, ignoreCase = true) }
        }?.toReleaseAsset()

    return when (platform) {
        UpdatePlatform.MacOs -> firstWithExtension(".dmg", ".pkg")
        UpdatePlatform.Windows -> firstWithExtension(".msi")
        UpdatePlatform.LinuxFlatpak -> firstWithExtension(".flatpak")
        UpdatePlatform.LinuxDeb -> firstWithExtension(".deb")
        UpdatePlatform.Android -> firstWithExtension(".apk")
        UpdatePlatform.Ios, UpdatePlatform.Web, UpdatePlatform.Other -> null
    }
}
