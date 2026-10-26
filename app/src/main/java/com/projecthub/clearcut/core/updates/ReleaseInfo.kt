package com.projecthub.clearcut.core.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Metadata about a published ClearCut release, decoded from the GitHub
 * Releases API (https://api.github.com/repos/{owner}/{repo}/releases/latest).
 */
@Serializable
data class ReleaseInfo(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("assets") val assets: List<Asset> = emptyList(),
) {
    /** The version embedded in [tagName] (e.g. `v1.1.0` -> `1.1.0`), or the tag itself. */
    val semver: String
        get() {
            val trimmed = tagName.removePrefix("v")
            val digits = Regex("""^(\d+(?:\.\d+){1,2})""").find(trimmed)?.groupValues?.getOrNull(1)
            return digits ?: trimmed
        }

    /** Stable releases only; drafts and prereleases are never offered as updates. */
    val isStable: Boolean get() = !draft && !prerelease

    @Serializable
    data class Asset(
        @SerialName("name") val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        @SerialName("size") val sizeBytes: Long = 0L,
    ) {
        /** The installable APK asset, if this release publishes one. */
        val isApk: Boolean get() = name.endsWith(".apk", ignoreCase = true)
    }

    companion object {
        val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}