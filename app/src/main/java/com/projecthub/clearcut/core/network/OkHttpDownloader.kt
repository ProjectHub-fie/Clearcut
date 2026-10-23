package com.projecthub.clearcut.core.network

import android.util.Log
import com.projecthub.clearcut.core.model.ClearCutError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Bounded, cancellable streaming downloader with SSRF-safe redirect validation,
 * timeouts, size caps and atomic finalization into a unique temporary file.
 *
 * [securityValidator] and [hostPreflight] default to the full SSRF-hardening
 * checks; injection exists only so transport-level behavior (HTTP statuses,
 * size caps, cancellation, partial-file cleanup) can be exercised in unit tests
 * without weakening the production defaults.
 */
class OkHttpDownloader(
    private val client: OkHttpClient,
    private val securityValidator: (String) -> UrlValidator.Result = UrlValidator::validate,
    private val hostPreflight: (String) -> Unit = ::defaultHostPreflight,
) : Downloader {

    override suspend fun download(
        url: String,
        onProgress: suspend (DownloadProgress) -> Unit,
        maxBytes: Long,
    ): DownloadResult = withContext(Dispatchers.IO) {
        downloadLoop(url, onProgress, maxBytes)
    }

    /** The redirect-capable download loop; returns a [DownloadResult] or throws. */
    private suspend fun downloadLoop(
        url: String,
        onProgress: suspend (DownloadProgress) -> Unit,
        maxBytes: Long,
    ): DownloadResult {
        var currentUrl = url
        var redirects = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val validated = securityValidator(currentUrl)
            if (!validated.isSuccess()) throw validated.failure!!
            // DNS-time SSRF re-check against resolved addresses
            hostPreflight(currentUrl)

            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", "ClearCut/1.0 (media import; " +
                    "platform video pages are not downloaded)")
                .header("Accept", "audio/*,video/*,application/octet-stream;q=0.9,*/*;q=0.1")
                .get()
                .build()

            val response: Response
            try {
                response = client.newCall(request).execute()
            } catch (e: java.net.SocketTimeoutException) {
                throw ClearCutError.NetworkError("The download timed out. Check your connection and retry.", e)
            } catch (e: javax.net.ssl.SSLException) {
                throw ClearCutError.NetworkError("TLS/certificate failure while contacting the server.", e)
            } catch (e: UnknownHostException) {
                throw ClearCutError.NetworkError("Could not resolve the server address (DNS failure).", e)
            } catch (e: IOException) {
                throw ClearCutError.NetworkError("Network error: ${e.message ?: "connection failed"}", e)
            }

            try {
                val code = response.code
                val location = response.header("Location")
                if (code in 300..399 && code != 304 && location != null) {
                    redirects++
                    if (redirects > 5) throw ClearCutError.NetworkError("Too many redirects (possible redirect loop).")
                    val newUrl = try {
                        response.request.url.resolve(location).toString()
                    } catch (e: Exception) {
                        throw ClearCutError.NetworkError("Unsafe redirect target: $location", e)
                    }
                    val nextValid = UrlValidator.validate(newUrl)
                    if (!nextValid.isSuccess()) throw nextValid.failure!!
                    currentUrl = newUrl
                    response.close()
                    continue
                }
                when (code) {
                    401 -> throw ClearCutError.NetworkError("Authentication is required to access this file (HTTP 401).")
                    403 -> throw ClearCutError.NetworkError("Access to this file is forbidden (HTTP 403). The server may require authorization or may block this downloader.")
                    404 -> throw ClearCutError.NetworkError("The file was not found (HTTP 404). The link may be expired or removed.")
                    413 -> throw ClearCutError.NetworkError("The file is larger than the server allows (HTTP 413).")
                    429 -> throw ClearCutError.NetworkError("The server is rate-limiting this request (HTTP 429). Wait a moment and retry.")
                }
                if (code >= 500) throw ClearCutError.HttpError(code, "The server reported an error (HTTP $code). Please retry later.")
                if (code in 200..299) {
                    val body = response.body ?: throw ClearCutError.NetworkError("Empty response body from server.")
                    val declaredContentType = body.contentType()?.toString()
                    val declaredLength = body.contentLength()
                    val lengthForLimit: Long? = if (declaredLength >= 0) declaredLength else null
                    if (lengthForLimit != null && lengthForLimit > maxBytes) {
                        throw ClearCutError.StorageError(
                            "The file is ${formatBytes(lengthForLimit)} which exceeds the configured download limit of ${formatBytes(maxBytes)}."
                        )
                    }
                    val contentTypeText = declaredContentType ?: response.header("Content-Type") ?: ""
                    if (contentTypeText.isNotBlank() && contentTypeText.contains("text/html") && !looksLikeMedia(contentTypeText)) {
                        throw ClearCutError.UnsupportedMediaError(
                            "The server returned an HTML page instead of media. This usually means the link is a video page, not a direct media file. Use a direct media link you are authorized to download."
                        )
                    }
                    val dest = streamToFile(body.byteStream(), declaredLength, onProgress, maxBytes)
                    return DownloadResult(
                        filePath = dest.absolutePath,
                        contentType = declaredContentType,
                        contentLength = declaredLength.takeIf { it >= 0 },
                        finalUrl = response.request.url.toString(),
                    )
                }
                throw ClearCutError.HttpError(code, "Unexpected HTTP response ($code).")
            } finally {
                response.close()
            }
        }
    }

    private suspend fun streamToFile(
        source: java.io.InputStream,
        knownLength: Long?,
        onProgress: suspend (DownloadProgress) -> Unit,
        maxBytes: Long,
    ): File {
        currentCoroutineContext().ensureActive()
        val tmp = File.createTempFile("clearcut_dl_", ".part")
        var total = 0L
        val buf = ByteArray(64 * 1024)
        try {
            tmp.outputStream().buffered().use { out ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buf)
                    if (read < 0) break
                    total += read
                    if (maxBytes > 0 && total > maxBytes) {
                        throw ClearCutError.StorageError("Download exceeded the configured size limit (${formatBytes(maxBytes)}).")
                    }
                    out.write(buf, 0, read)
                    if (total % (512 * 1024) == 0L) {
                        val progress = knownLength?.let { (total.toFloat() / it).coerceIn(0f, 1f) }
                        onProgress(DownloadProgress(total, knownLength, progress ?: 0f))
                    }
                }
            }
            onProgress(DownloadProgress(total, knownLength, if (knownLength != null && knownLength > 0) 1f else 0f))
            if (total == 0L) {
                throw ClearCutError.NetworkError("The download was empty (0 bytes). The link may be expired.")
            }
            if (knownLength != null && total < knownLength * 0.95 && knownLength > 1_000_000) {
                throw ClearCutError.NetworkError(
                    "The download was interrupted: received ${formatBytes(total)} of ${formatBytes(knownLength)}."
                )
            }
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            if (e is ClearCutError) throw e
            if (e is kotlinx.coroutines.CancellationException) {
                runCatching { tmp.delete() }
                throw e
            }
            throw ClearCutError.NetworkError("Download failed: ${e.message ?: "unknown error"}", e)
        }
        return tmp
    }

    private fun looksLikeMedia(contentType: String): Boolean {
        val ct = contentType.lowercase()
        return ct.startsWith("audio/") || ct.startsWith("video/") ||
            ct.contains("octet-stream") || ct.contains("matroska") ||
            ct.contains("quicktime")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Production SSRF guard: verify host resolves only to public addresses. */
private fun defaultHostPreflight(url: String) {
    val uri = try {
        URI(url)
    } catch (e: Exception) {
        throw ClearCutError.ValidationError("Invalid URL.")
    }
    val host = uri.host ?: return
    if (UrlValidator.isProhibitedIp(host)) {
        throw ClearCutError.NetworkError("Access to network destination '$host' is not permitted.")
    }
    val addresses = try {
        InetAddress.getAllByName(host)
    } catch (e: UnknownHostException) {
        throw ClearCutError.NetworkError("Could not resolve the server address (DNS failure).", e)
    }
    for (addr in addresses) {
        if (UrlValidator.isProhibitedIp(addr)) {
            Log.w("ClearCutDownloader", "Refusing address ${addr.hostAddress} for host $host (reserved range).")
            throw ClearCutError.NetworkError("Destination '$host' resolves to a reserved or private address and is not permitted.")
        }
    }
}