package com.projecthub.clearcut.core.updates

import com.projecthub.clearcut.core.model.ClearCutError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Simple semver comparison for `MAJOR[.MINOR[.PATCH]]` labels. Missing
 * components default to zero (e.g. `1.2` == `1.2.0`).
 */
object VersionCompare {
    fun parts(version: String): List<Int> =
        Regex("""\d+""").findAll(version)
            .map { it.value.toInt() }
            .toList()
            .let { it + List(3 - it.size.coerceAtMost(3)) { 0 } }

    /** True when [candidate] is a strictly newer semver than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until a.size) {
            if (a[i] > b[i]) return true
            if (a[i] < b[i]) return false
        }
        return false
    }
}

/**
 * Queries the ClearCut GitHub repository for the newest stable release and
 * compares it against the running app version.
 *
 * The check is deliberately read-only: it hits the public GitHub Releases API
 * using the same hardened OkHttp client as URL imports and reports failures as
 * [ClearCutError] so callers can surface a friendly message.
 */
class UpdateChecker(
    private val client: OkHttpClient,
    private val releasesApi: String = DEFAULT_RELEASES_API,
) {
    /**
     * @return a [ReleaseInfo] when the remote release is strictly newer than
     *         [currentVersion], otherwise `null`.
     * @throws ClearCutError for network, HTTP or parse failures.
     */
    suspend fun checkForUpdate(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        val latest = fetchLatestRelease() ?: return@withContext null
        if (!latest.isStable) return@withContext null
        if (VersionCompare.isNewer(latest.semver, currentVersion)) latest else null
    }

    /** Fetches and decodes the latest release payload. Returns null when there are none. */
    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(releasesApi)
            .header("User-Agent", "ClearCut-update-check")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw ClearCutError.NetworkError("The update check timed out. Check your connection and retry.", e)
        } catch (e: UnknownHostException) {
            throw ClearCutError.NetworkError("Could not reach the update server (DNS failure).", e)
        } catch (e: java.io.IOException) {
            throw ClearCutError.NetworkError("Network error during the update check: ${e.message ?: "connection failed"}", e)
        }

        response.use { res ->
            val code = res.code
            if (code == 404 || code == 410) return@use null // no releases published yet
            if (code !in 200..299) {
                throw ClearCutError.HttpError(code, "The update server responded with HTTP $code.")
            }
            val body = res.body?.string() ?: throw ClearCutError.NetworkError("Empty response from the update server.")
            try {
                ReleaseInfo.json.decodeFromString<ReleaseInfo>(body)
            } catch (e: Exception) {
                throw ClearCutError.AnalysisError("The update response could not be parsed.", e)
            }
        }
    }

    companion object {
        const val DEFAULT_RELEASES_API = "https://api.github.com/repos/ProjectHub-fie/Clearcut/releases/latest"

        fun defaultClient(): OkHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}