package com.projecthub.clearcut.core.network

import com.projecthub.clearcut.core.model.ClearCutError
import kotlinx.coroutines.flow.Flow

/**
 * A source adapter knows how to obtain a downloadable media URL from a user URL
 * using official provider APIs or authorized integrations. Adapters are never
 * allowed to extract login cookies, bypass DRM, or scrape anti-bot walls.
 */
interface SourceAdapter {
    val sourceId: String
    val displayName: String

    /** True when [url] is a URL this adapter understands. */
    fun handles(url: String): Boolean

    /**
     * Return a direct, authorized media URL for [url].
     * @throws ClearCutError when the source cannot be accessed lawfully.
     */
    suspend fun resolveDirectUrl(url: String): DirectMedia

    data class DirectMedia(val url: String, val expectedContentType: String?, val expectedSizeBytes: Long?)
}

/**
 * Default direct-download adapter: the URL itself is the media URL.
 * Works for any http(s) direct media link.
 */
class DirectMediaAdapter : SourceAdapter {
    override val sourceId = "direct"
    override val displayName = "Direct media link"
    override fun handles(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")
    override suspend fun resolveDirectUrl(url: String): SourceAdapter.DirectMedia =
        SourceAdapter.DirectMedia(url, null, null)
}

/**
 * Registry of supported adapters. Adding a provider = registering a new adapter.
 */
class SourceAdapterRegistry(private val adapters: List<SourceAdapter>) {
    fun find(url: String): SourceAdapter? = adapters.firstOrNull { it.handles(url) }
    val available: List<SourceAdapter> get() = adapters
}

/**
 * Provides download progress (0f..1f) and byte transfer.
 */
data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long?, val progress: Float) {
    val hasKnownSize: Boolean get() = totalBytes != null && totalBytes > 0
}

interface Downloader {
    /** Stream-downloads [url] into a fresh temporary file, returning the finished file. */
    suspend fun download(
        url: String,
        onProgress: suspend (DownloadProgress) -> Unit,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): DownloadResult

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024 * 1024 // 2 GiB
    }
}

data class DownloadResult(
    val filePath: String,
    val contentType: String?,
    val contentLength: Long?,
    val finalUrl: String,
)