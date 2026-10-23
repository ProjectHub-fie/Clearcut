package com.projecthub.clearcut.feature.import

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.Locale
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.MediaSource
import com.projecthub.clearcut.core.model.OperationState
import com.projecthub.clearcut.core.network.DownloadProgress
import com.projecthub.clearcut.core.storage.StorageHelper
import com.projecthub.clearcut.di.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class ImportResult(
    val media: MediaInfo,
    val localCopyOnDisk: File?,
)

class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as com.projecthub.clearcut.app.ClearCutApplication).container

    private val _importState = MutableStateFlow<OperationState<ImportResult>>(OperationState.Idle)
    val importState: StateFlow<OperationState<ImportResult>> = _importState.asStateFlow()

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _urlDraft = MutableStateFlow("")
    val urlDraft: StateFlow<String> = _urlDraft.asStateFlow()

    fun setUrlDraft(value: String) { _urlDraft.value = value }

    private val _recoveryAction = MutableStateFlow<String?>(null)
    val recoveryAction: StateFlow<String?> = _recoveryAction.asStateFlow()

    private val _urlInfo = MutableStateFlow<UrlPreflight?>(null)
    val urlInfo: StateFlow<UrlPreflight?> = _urlInfo.asStateFlow()

    data class UrlPreflight(val displayUrl: String, val sourceLabel: String, val sizeLabel: String?)

    fun clearState() {
        _importState.value = OperationState.Idle
        _downloadProgress.value = null
        _recoveryAction.value = null
        _urlInfo.value = null
    }

    /** Import from Storage Access Framework / picker result URIs. */
    fun importLocal(uri: Uri) {
        if (_importState.value.isActive) return  // prevent duplicates
        viewModelScope.launch {
            _importState.value = OperationState.Validating("Opening file…")
            try {
                val size = StorageHelper.uriSize(getApplication(), uri)
                val name = StorageHelper.displayName(getApplication(), uri)
                var copy: File? = null
                var inspectUri = uri
                if (uri.scheme == "file" || uri.scheme == "content") {
                    // Copy into app cache for stable access across permission loss.
                    val cacheDir: File = java.io.File(getApplication<Application>().cacheDir, "imports")
                    cacheDir.mkdirs()
                    _importState.value = OperationState.InProgress("Copying file", null)
                    copy = StorageHelper.copiesSourceIntoFile(getApplication(), uri, cacheDir)
                    inspectUri = Uri.fromFile(copy)
                }
                _importState.value = OperationState.Validating("Inspecting media…")
                val media = container.mediaInspector.inspect(
                    uri = inspectUri,
                    source = MediaSource.LOCAL,
                    displayName = name,
                    sizeBytes = size,
                )
                _importState.value = OperationState.Completed(ImportResult(media, copy))
            } catch (e: Throwable) {
                val err = ClearCutError.fromThrowable(e, "import")
                _importState.value = OperationState.Failed(err)
                _recoveryAction.value = recoveryFor(err)
            }
        }
    }

    /** Preflight a URL before downloading: validate + adapt + inspect headers. */
    fun preflightUrl(rawUrl: String) {
        if (_importState.value.isActive) return
        val url = rawUrl.trim()
        _recoveryAction.value = null
        _urlInfo.value = null
        viewModelScope.launch {
            _importState.value = OperationState.Validating("Checking URL…")
            try {
                val validation = com.projecthub.clearcut.core.network.UrlValidator.validate(url)
                if (!validation.isSuccess()) throw validation.failure!!
                val validated = validation.getOrNull()!!
                val adapter = container.sourceAdapterRegistry.find(url)
                    ?: throw ClearCutError.UnsupportedMediaError("No enabled source adapter handles this URL.")
                val direct = adapter.resolveDirectUrl(url)
                val headerInfo = probeHeaders(direct.url)
                val sizeLabel = direct.expectedSizeBytes?.let { formatBytes(it) }
                    ?: headerInfo.length?.let { formatBytes(it) }
                _urlInfo.value = UrlPreflight(
                    displayUrl = validated.normalizedUrl,
                    sourceLabel = adapter.displayName,
                    sizeLabel = sizeLabel,
                )
                _importState.value = OperationState.Idle
            } catch (e: Throwable) {
                val err = ClearCutError.fromThrowable(e, "URL check")
                _importState.value = OperationState.Failed(err)
                _recoveryAction.value = recoveryFor(err)
            }
        }
    }

    /** Confirmed URL import with progress, cancellation, bounded bytes. */
    fun importUrl(confirmOverride: Boolean = false) {
        if (_importState.value.isActive) return
        val url = _urlDraft.value.trim()
        if (url.isEmpty()) {
            _importState.value = OperationState.Failed(ClearCutError.ValidationError("Enter a URL first."))
            return
        }
        viewModelScope.launch {
            _importState.value = OperationState.Validating("Preparing download…")
            try {
                val validation = com.projecthub.clearcut.core.network.UrlValidator.validate(url)
                if (!validation.isSuccess()) throw validation.failure!!
                val validated = validation.getOrNull()!!
                val adapter = container.sourceAdapterRegistry.find(url)
                    ?: throw ClearCutError.UnsupportedMediaError("No enabled source adapter handles this URL.")
                val direct = adapter.resolveDirectUrl(url)
                val prefs = container.preferencesStore.preferences.first()
                val maxBytes = prefs.maxDownloadBytes
                val destDir: File = java.io.File(getApplication<Application>().cacheDir, "downloads")
                destDir.mkdirs()

                val result = container.downloader.download(
                    url = direct.url,
                    onProgress = { p -> _downloadProgress.value = p },
                    maxBytes = maxBytes,
                )

                // Validate independently of the server's claimed content type.
                _importState.value = OperationState.Validating("Validating downloaded file…")
                val file = File(result.filePath)
                val media = container.mediaInspector.inspect(
                    uri = Uri.fromFile(file),
                    source = MediaSource.URL,
                    displayName = file.name.replaceFirst(".part", ""),
                    sizeBytes = file.length(),
                )
                // Move to stable imports dir
                val importDir: File = java.io.File(getApplication<Application>().cacheDir, "imports")
                importDir.mkdirs()
                val final = java.io.File(importDir, derivedName(file, media.mimeType))
                if (final.exists()) final.delete()
                if (!file.renameTo(final)) {
                    file.copyTo(final, overwrite = true)
                    file.delete()
                }
                _importState.value = OperationState.Completed(
                    ImportResult(
                        media = media.copy(uri = Uri.fromFile(final).toString(), displayName = final.name),
                        localCopyOnDisk = final,
                    )
                )
                _downloadProgress.value = null
            } catch (e: Throwable) {
                val err = ClearCutError.fromThrowable(e, "download")
                _downloadProgress.value = null
                _importState.value = OperationState.Failed(err)
                _recoveryAction.value = recoveryFor(err)
            }
        }
    }

    fun cancelImport() {
        // The OkHttp call itself is cancellable through coroutine cancellation.
    }

    private suspend fun probeHeaders(url: String): HeaderInfo {
        return try {
            val req = okhttp3.Request.Builder().url(url).head().build()
            val client = com.projecthub.clearcut.core.network.OkHttpDownloader.defaultClient()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    HeaderInfo(
                        length = resp.header("Content-Length")?.toLongOrNull(),
                        contentType = resp.header("Content-Type"),
                    )
                } else {
                    HeaderInfo(null, null)
                }
            }
        } catch (e: Exception) {
            HeaderInfo(null, null)
        }
    }

    private fun recoveryFor(err: ClearCutError): String = when (err.code) {
        "HTTP_401", "HTTP_403" -> "The server requires authorization. Use a direct link you are authorized to access, or import a local file instead."
        "UNSUPPORTED_MEDIA" -> "This source is not directly downloadable in ClearCut. Import a local file or a direct media link instead."
        "STORAGE" -> "There is not enough storage space. Free space and retry."
        "NETWORK" -> "Check your connection and confirm the link is still valid."
        "CANCELLED" -> "The download was cancelled."
        else -> "Retry, or import a local file instead."
    }

    private fun derivedName(partFile: File, mime: String?): String {
        val ext = when (mime) {
            "audio/mpeg" -> ".mp3"
            "audio/mp4", "audio/mp4a-latm" -> ".m4a"
            "audio/x-wav", "audio/wav" -> ".wav"
            "video/mp4", "video/avc" -> ".mp4"
            "video/webm" -> ".webm"
            "video/x-matroska" -> ".mkv"
            "video/quicktime" -> ".mov"
            else -> partFile.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".mp4"
        }
        return "clearcut_download_${UUID.randomUUID().toString().take(8)}$ext"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val mb = bytes / 1024.0 / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    private data class HeaderInfo(val length: Long?, val contentType: String?)

    companion object {
        val Factory = viewModelFactory { app, _ -> ImportViewModel(app) }
    }
}