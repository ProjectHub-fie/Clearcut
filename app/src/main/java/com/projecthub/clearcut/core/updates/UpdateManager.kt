package com.projecthub.clearcut.core.updates

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.network.Downloader
import com.projecthub.clearcut.core.network.DownloadProgress
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Downloads a new release APK and hands it to the Android package installer.
 *
 * The APK is streamed into ClearCut's cache dir (matching the FileProvider
 * `<cache-path>` entry) then opened with an `ACTION_VIEW` intent using an
 * `application/vnd.android.package-archive` type. Installing an app from an
 * unknown source requires the user to approve the system prompt; ClearCut
 * never requests install permissions itself.
 */
class UpdateManager(
    private val context: Context,
    private val downloader: Downloader,
) {

    private fun progressOf(p: DownloadProgress): Float =
        if (p.hasKnownSize && p.totalBytes != null && p.totalBytes > 0) (p.bytesDownloaded.toFloat() / p.totalBytes).coerceIn(0f, 1f)
        else 0f

    data class DownloadOutcome(val apkFile: File, val sizeBytes: Long)

    /**
     * Downloads the given [url] into the app cache and returns the local APK
     * file. Throws [ClearCutError] on any failure.
     */
    suspend fun downloadApk(url: String, expectedBytes: Long, onProgress: suspend (Float) -> Unit): DownloadOutcome {
        coroutineContext.ensureActive()
        val result = downloader.download(url, onProgress = { p -> onProgress(progressOf(p)) }, maxBytes = MAX_APK_BYTES)
        val tmp = File(result.filePath)
        if (!tmp.exists() || tmp.length() == 0L) {
            throw ClearCutError.NetworkError("The update download was empty. Please retry.")
        }
        if (expectedBytes > 0 && tmp.length() != expectedBytes) {
            throw ClearCutError.NetworkError(
                "The downloaded update (${tmp.length()} bytes) does not match the published size (${expectedBytes} bytes)."
            )
        }
        // The FileProvider only exposes our cache dir; relocate the streamed temp
        // file there so the package installer can be granted read access.
        val destination = File(context.cacheDir, "updates/clearcut-update.apk")
        destination.parentFile?.mkdirs()
        if (!tmp.renameTo(destination)) {
            try {
                tmp.copyTo(destination, overwrite = true)
                tmp.delete()
            } catch (e: java.io.IOException) {
                throw ClearCutError.StorageError("Could not stage the update file for installation.", e)
            }
        }
        return DownloadOutcome(destination, destination.length())
    }

    /**
     * Installs a downloaded [apkFile] by launching the package installer.
     * @throws ClearCutError when no installer can handle the APK.
     */
    fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(install)
        } catch (e: ActivityNotFoundException) {
            throw ClearCutError.PermissionError("No package installer was found on this device.")
        }
    }

    /** The download cap (1.5 GB) generously exceeds any realistic ClearCut APK. */
    companion object {
        const val MAX_APK_BYTES = 1536L * 1024 * 1024
    }
}