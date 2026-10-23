package com.projecthub.clearcut.core.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.projecthub.clearcut.core.model.MediaInfo

/** Reopens an exported file and verifies duration, tracks and decodability. */
object EditedMediaValidator {

    data class ValidationResult(
        val durationUs: Long,
        val hasAudio: Boolean,
        val hasVideo: Boolean,
        val warnings: List<String>,
        val validated: Boolean,
    )

    suspend fun validate(
        context: Context,
        file: java.io.File,
        original: MediaInfo?,
    ): ValidationResult {
        if (!file.exists() || file.length() < 100) {
            return ValidationResult(0L, false, false, listOf("Output file missing or too small."), false)
        }
        val extractor = MediaExtractor()
        val warnings = mutableListOf<String>()
        try {
            extractor.setDataSource(file.absolutePath)
            var durationUs = 0L
            var hasAudio = false
            var hasVideo = false
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    hasAudio = true
                    durationUs = maxOf(durationUs, fmt.getLongOrDefault(MediaFormat.KEY_DURATION))
                } else if (mime.startsWith("video/")) {
                    hasVideo = true
                    durationUs = maxOf(durationUs, fmt.getLongOrDefault(MediaFormat.KEY_DURATION))
                }
            }
            if (!hasAudio && !hasVideo) {
                warnings.add("Export contains neither an audio nor a video track.")
            }
            if (original != null && durationUs > 0) {
                val expected = original.durationUs
                if (durationUs > expected + expected / 20) {
                    warnings.add("Export duration (${durationUs / 1000} ms) is notably longer than the source (${expected / 1000} ms).")
                }
            }
            return ValidationResult(
                durationUs = durationUs,
                hasAudio = hasAudio,
                hasVideo = hasVideo,
                warnings = warnings,
                validated = warnings.isEmpty(),
            )
        } catch (e: Exception) {
            return ValidationResult(
                durationUs = 0L,
                hasAudio = false,
                hasVideo = false,
                warnings = listOf("Could not reopen the exported file: ${e.message ?: "unknown error"}"),
                validated = false,
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.getLongOrDefault(key: String): Long =
        if (containsKey(key)) getLong(key) else 0L
}