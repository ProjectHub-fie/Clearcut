package com.projecthub.clearcut.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.projecthub.clearcut.core.model.AudioTrackInfo
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.MediaSource
import com.projecthub.clearcut.core.model.MediaType
import com.projecthub.clearcut.core.model.TimeRange
import com.projecthub.clearcut.core.model.VideoTrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Validates a media file and inspects its container, tracks, codecs and duration
 * independently of its file extension, using [MediaMetadataRetriever] plus Media3
 * probe extraction where available.
 */
class MediaInspector(private val context: Context) {

    private val supportedContainers = setOf(
        "video/mp4", "video/x-matroska", "video/quicktime", "video/webm",
        "audio/mpeg", "audio/mp4", "audio/aac", "audio/x-wav", "audio/wav",
        "audio/ogg", "audio/flac", "audio/3gpp", "video/3gpp", "video/mp2t",
        "audio/amr", "audio/x-m4a",
    )

    private val supportedAudioMimes = setOf(
        "audio/mpeg", "audio/mp4", "audio/aac", "audio/x-wav", "audio/ogg",
        "audio/flac", "audio/amr", "audio/3gpp", "audio/mp4a-latm",
        "audio/raw", "audio/vnd.dts", "audio/ac3", "audio/eac3",
    )

    private val supportedVideoMimes = setOf(
        "video/avc", "video/hevc", "video/mp4v-es", "video/3gpp", "video/webm",
        "video/vp8", "video/vp9", "video/mp2t", "video/mpeg", "video/av01",
        "video/x-msvideo", "video/mp4",
    )

    /** Validates that the file is decodable and returns full [MediaInfo]. */
    suspend fun inspect(
        uri: Uri,
        source: MediaSource,
        displayName: String = "media",
        sizeBytes: Long = 0L,
    ): MediaInfo = withContext(Dispatchers.IO) {
        try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                extract(r, uri, source, displayName, sizeBytes)
            } finally {
                runCatching { r.release() }
            }
        } catch (e: ClearCutError) {
            throw e
        } catch (e: Exception) {
            throw ClearCutError.DecodeError(
                "The selected file could not be decoded. It may be corrupted or use an unsupported codec. (${e.message ?: "unknown reason"})",
                e,
            )
        }
    }

    private fun extract(
        r: MediaMetadataRetriever,
        uri: Uri,
        source: MediaSource,
        displayName: String,
        sizeBytes: Long,
    ): MediaInfo {
        fun meta(key: Int): String? = runCatching { r.extractMetadata(key) }.getOrNull()

        val durationUs = (meta(METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
        val mime = meta(METADATA_KEY_MIMETYPE)
        val hasAudio = meta(METADATA_KEY_HAS_AUDIO)?.let { it == "yes" } ?: (mime?.startsWith("audio/") == true)
        val hasVideo = meta(METADATA_KEY_HAS_VIDEO)?.let { it == "yes" } ?: (mime?.startsWith("video/") == true)
        val width = meta(METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = meta(METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = meta(METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val frameRate = meta(METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 0f
        val sampleRate = meta(METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
        val bitrate = meta(METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0

        val type = when {
            hasVideo -> MediaType.VIDEO
            hasAudio -> MediaType.AUDIO
            else -> MediaType.VIDEO
        }

        if (!hasAudio && !hasVideo) {
            throw ClearCutError.UnsupportedMediaError(
                "No playable audio or video track was found in this file."
            )
        }

        val containerOk = mime == null || supportedContainers.any { mime.startsWith(it.replace("*", "")) } ||
            mime.startsWith("audio/") || mime.startsWith("video/")
        if (!containerOk) {
            throw ClearCutError.UnsupportedMediaError(
                "Unsupported container type '$mime'. Supported formats include MP4, MKV, MOV, MP3, AAC, M4A, WAV and OGG."
            )
        }
        if (durationUs <= 0L) {
            throw ClearCutError.UnsupportedMediaError(
                "The file reported no duration. It may be empty, truncated, or use an unsupported container."
            )
        }
        if (sizeBytes > 0L && sizeBytes < 100) {
            throw ClearCutError.ValidationError("The file is too small to contain usable media.")
        }

        val audioTracks = if (hasAudio) {
            listOf(
                AudioTrackInfo(
                    index = 0,
                    mimeType = meta(METADATA_KEY_MIMETYPE)?.takeIf { it.startsWith("audio/") },
                    language = null,
                    sampleRate = sampleRate,
                    channelCount = runCatching { meta(METADATA_KEY_NUM_TRACKS) }.getOrNull()?.let { 0 } ?: 0,
                ),
            )
        } else emptyList()

        val videoTracks = if (hasVideo) {
            listOf(
                VideoTrackInfo(
                    index = 0,
                    mimeType = meta(METADATA_KEY_MIMETYPE)?.takeIf { it.startsWith("video/") },
                    width = width,
                    height = height,
                    frameRate = frameRate,
                ),
            )
        } else emptyList()

        return MediaInfo(
            uri = uri.toString(),
            displayName = displayName,
            mimeType = mime,
            sizeBytes = sizeBytes,
            durationUs = durationUs,
            mediaType = type,
            containerMimeType = mime,
            audioTracks = audioTracks,
            videoTracks = videoTracks,
            rotationDegrees = rotation,
            width = width,
            height = height,
            frameRate = frameRate,
            hasAudio = hasAudio,
            hasVideo = hasVideo,
            source = source,
        )
    }

    /** Cheap sync validation used before committing to a scan. */
    fun probeFile(file: File): Boolean {
        return file.exists() && file.length() > 100
    }

    companion object {
        private const val METADATA_KEY_DURATION = android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
        private const val METADATA_KEY_MIMETYPE = android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE
        private const val METADATA_KEY_HAS_AUDIO = android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
        private const val METADATA_KEY_HAS_VIDEO = android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
        private const val METADATA_KEY_VIDEO_WIDTH = android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        private const val METADATA_KEY_VIDEO_HEIGHT = android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        private const val METADATA_KEY_VIDEO_ROTATION = android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
        private const val METADATA_KEY_CAPTURE_FRAMERATE = android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
        private const val METADATA_KEY_SAMPLERATE = android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE
        private const val METADATA_KEY_BITRATE = android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE
        private const val METADATA_KEY_NUM_TRACKS = android.media.MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS
    }
}