package com.projecthub.clearcut.core.model

@kotlinx.serialization.Serializable
data class RenderRequest(
    val sourceUri: String,
    val edl: Edl,
    val outputPath: String,
    val requestedWidth: Int? = null,
    val requestedHeight: Int? = null,
    val requestedBitrate: Int? = null,
    val expectedSizeBytes: Long = 0L,
    val requestedVideoMime: String? = null,
    val requestedAudioMime: String? = null,
)