package com.projecthub.clearcut.core.model

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class TimeRange(val startUs: Long, val endUs: Long) {
    val durationUs: Long get() = (endUs - startUs).coerceAtLeast(0L)
    fun contains(us: Long): Boolean = us >= startUs && us <= endUs
    fun overlaps(other: TimeRange): Boolean = startUs <= other.endUs && other.startUs <= endUs
    fun mergeWith(other: TimeRange): TimeRange =
        TimeRange(minOf(startUs, other.startUs), maxOf(endUs, other.endUs))
    override fun toString(): String = "TimeRange(${formatUs(startUs)} - ${formatUs(endUs)})"

    companion object {
        fun formatUs(us: Long): String {
            val totalMs = us / 1000
            val h = totalMs / 3_600_000
            val m = (totalMs % 3_600_000) / 60_000
            val s = (totalMs % 60_000) / 1000
            val ms = totalMs % 1000
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, ms)
            else String.format(Locale.US, "%d:%02d.%03d", m, s, ms)
        }
    }
}

@Serializable
enum class MediaType { AUDIO, VIDEO }

@Serializable
data class AudioTrackInfo(
    val index: Int,
    val mimeType: String?,
    val language: String? = null,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val bitrate: Int = 0,
)

@Serializable
data class VideoTrackInfo(
    val index: Int,
    val mimeType: String?,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val bitrate: Int = 0,
)

@Serializable
data class MediaInfo(
    val uri: String,
    val displayName: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    val durationUs: Long = 0L,
    val mediaType: MediaType = MediaType.VIDEO,
    val containerMimeType: String? = null,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val videoTracks: List<VideoTrackInfo> = emptyList(),
    val rotationDegrees: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val hasAudio: Boolean = false,
    val hasVideo: Boolean = false,
    val source: MediaSource = MediaSource.UNKNOWN,
) {
    val isAudioOnly: Boolean get() = mediaType == MediaType.AUDIO || (hasAudio && !hasVideo)
}

@Serializable
enum class MediaSource { LOCAL, URL, SYNTHETIC, UNKNOWN }

@Serializable
data class StorageUsage(
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBytes: Long,
) {
    val canAllocate: Boolean get() = freeBytes > 0
}
