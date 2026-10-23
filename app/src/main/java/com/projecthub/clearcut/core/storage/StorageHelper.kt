package com.projecthub.clearcut.core.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.StorageUsage
import java.io.File

/** Storage validation and usage reporting. */
object StorageHelper {

    fun usage(context: Context): StorageUsage {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return usageOf(dir)
    }

    fun usageOf(dir: File): StorageUsage {
        val stat = android.os.StatFs(dir.absolutePath)
        val total = stat.totalBytes
        val available = stat.availableBytes
        return StorageUsage(
            totalBytes = total,
            freeBytes = available,
            usedBytes = (total - available).coerceAtLeast(0),
        )
    }

    fun ensureSpace(dir: File, neededBytes: Long): Boolean {
        val stat = android.os.StatFs(dir.absolutePath)
        return stat.availableBytes >= neededBytes
    }

    /** Byte size of a content URI via the content provider. */
    fun uriSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                if (fd.length > 0) fd.length else fd.declaredLength
            } ?: querySize(context, uri)
        } catch (e: Exception) {
            querySize(context, uri)
        }
    }

    private fun querySize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else null
                } else null
            } ?: uri.lastPathSegment ?: "media"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "media"
        }
    }

    fun copiesSourceIntoFile(context: Context, uri: Uri, destDir: File): File {
        try {
            val name = displayName(context, uri)
            val out = File(destDir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: throw ClearCutError.StorageError("Could not open the selected file.")
            return out
        } catch (e: ClearCutError) {
            throw e
        } catch (e: Exception) {
            throw ClearCutError.DecodeError("Could not copy the selected file into app storage.", e)
        }
    }
}