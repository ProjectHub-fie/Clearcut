package com.projecthub.clearcut.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/* ---------------------------------------------------------------------------------------------
 * Timeline editing model.
 *
 * Inspired by the professional editing concepts popularised by mobile editors such as KineMaster:
 * a primary media timeline plus timeline overlay layers, per-clip effects, transitions,
 * volume envelopes, keyframes and multiple audio tracks. Everything below is serializable so an
 * in-progress edit survives process death and can be shared (project export / templates).
 * --------------------------------------------------------------------------------------------- */

/** The visual medium of a clip. */
@Serializable
enum class ClipKind { VIDEO, IMAGE, TEXT, PICTURE_OVERLAY, AUDIO, VOICEOVER }

@Serializable
enum class TrackKind { VIDEO, OVERLAY, TEXT, AUDIO, VOICEOVER, MUSIC }

/** Where an overlay clip is anchored on the canvas. */
@Serializable
enum class OverlayAnchor {
    CENTER, TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    MIDDLE_LEFT, MIDDLE_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
}

/** How a blend / overlay clip composites over the layers below it. */
@Serializable
enum class BlendMode(val label: String) {
    NORMAL("Normal"),
    ADD("Add"),
    MULTIPLY("Multiply"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    DARKEN("Darken"),
    LIGHTEN("Lighten"),
    DIFFERENCE("Difference"),
}

/** Standard video scene transitions. */
@Serializable
enum class TransitionKind(val label: String) {
    NONE("None"),
    CUT("Cut"),
    FADE_BLACK("Fade to black"),
    CROSSFADE("Crossfade"),
    FADE_WHITE("Fade to white"),
    SLIDE("Slide"),
    ZOOM("Zoom"),
}

@Serializable
enum class TextPreset(val label: String) {
    NONE("None"),
    TITLE("Title"),
    SUBTITLE("Subtitle"),
    FOOTER("Footer"),
}

/** Speed is stored as a multiplier; >1 = faster, <1 = slower. */
@Serializable
data class SpeedSettings(
    val speed: Float = 1f,
    val pitchPreserved: Boolean = true,
)

@Serializable
data class ColorGrade(
    val brightness: Float = 0f,   // -1..1
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 0f,   // -100..100
    val hue: Float = 0f,          // degrees -180..180
    val lightness: Float = 0f,    // -100..100
) {
    val isNoOp: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f && hue == 0f && lightness == 0f
}

@Serializable
data class ChromaKeySettings(
    val enabled: Boolean = false,
    val hueDegrees: Float = 120f,
    val toleranceDegrees: Float = 30f,
    val smoothing: Float = 0.05f,
)

/** Corner pin maps a rectangular clip to an arbitrary quad (warp / surfaces). */
@Serializable
data class CornerPin(
    val topLeft: OffsetFraction = OffsetFraction.ORIGIN,
    val topRight: OffsetFraction = OffsetFraction(1f, 0f),
    val bottomRight: OffsetFraction = OffsetFraction(1f, 1f),
    val bottomLeft: OffsetFraction = OffsetFraction(0f, 1f),
) {
    val isIdentity: Boolean
        get() = topLeft == OffsetFraction.ORIGIN &&
            topRight == OffsetFraction(1f, 0f) &&
            bottomRight == OffsetFraction(1f, 1f) &&
            bottomLeft == OffsetFraction(0f, 1f)
}

/** Normalized [0..1] coordinates on the output canvas. */
@Serializable
data class OffsetFraction(val x: Float, val y: Float) {
    companion object {
        val ORIGIN = OffsetFraction(0f, 0f)
        val CENTER = OffsetFraction(0.5f, 0.5f)
    }
}

@Serializable
data class PiPTransform(
    val scaleX: Float = 0.35f,
    val scaleY: Float = 0.35f,
    val rotationDegrees: Float = 0f,
    val anchor: OverlayAnchor = OverlayAnchor.CENTER,
    val offsetXFraction: Float = 0f,   // relative offset from the anchor
    val offsetYFraction: Float = 0f,
) {
    val isDefault: Boolean get() = scaleX == 0.35f && scaleY == 0.35f && rotationDegrees == 0f &&
        anchor == OverlayAnchor.CENTER && offsetXFraction == 0f && offsetYFraction == 0f
}

@Serializable
enum class AudioEffectType(val label: String) {
    ECHO("Echo"),
    REVERB("Reverb"),
    PITCH_SHIFT("Pitch shift"),
    EQUALIZER("Equalizer"),
    LOWPASS("Low-pass"),
    HIGHPASS("High-pass"),
    ROBOT("Robot voice"),
    CHIPMUNK("Chipmunk"),
}

@Serializable
data class AudioEffectPreset(
    val type: AudioEffectType,
    val intensity: Float = 0.5f,     // how strong the effect is
    val pitchSemitones: Float = 0f,  // pitch shift in semitones
)

/** A keyframe animates a scalar property over the clip's lifetime. */
@Serializable
data class Keyframe(
    val timeFraction: Float,   // 0..1 within the clip
    val value: Float,
)

@Serializable
data class KeyframedParameter(
    val name: String,
    val keyframes: List<Keyframe> = emptyList(),
    val defaultValue: Float = 1f,
) {
    fun valueAt(timeFraction: Float): Float {
        if (keyframes.isEmpty()) return defaultValue
        if (keyframes.size == 1) return keyframes.first().value
        val sorted = keyframes.sortedBy { it.timeFraction }
        if (timeFraction <= sorted.first().timeFraction) return sorted.first().value
        if (timeFraction >= sorted.last().timeFraction) return sorted.last().value
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (timeFraction in a.timeFraction..b.timeFraction) {
                val span = (b.timeFraction - a.timeFraction).coerceAtLeast(1e-6f)
                val t = ((timeFraction - a.timeFraction) / span).coerceIn(0f, 1f)
                return a.value + (b.value - a.value) * t
            }
        }
        return defaultValue
    }
}

/** A continuous volume envelope over the clip (amplitude 0..1). */
@Serializable
data class VolumeEnvelope(
    val points: List<VolumePoint> = emptyList(),
    val baseGain: Float = 1f,
) {
    fun gainAt(timeFraction: Float): Float {
        if (points.isEmpty()) return baseGain
        val sorted = points.sortedBy { it.timeFraction }
        if (sorted.size == 1) return baseGain * sorted.first().gain
        if (timeFraction <= sorted.first().timeFraction) return baseGain * sorted.first().gain
        if (timeFraction >= sorted.last().timeFraction) return baseGain * sorted.last().gain
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (timeFraction in a.timeFraction..b.timeFraction) {
                val span = (b.timeFraction - a.timeFraction).coerceAtLeast(1e-6f)
                val t = ((timeFraction - a.timeFraction) / span).coerceIn(0f, 1f)
                return baseGain * (a.gain + (b.gain - a.gain) * t)
            }
        }
        return baseGain
    }
}

@Serializable
data class VolumePoint(val timeFraction: Float, val gain: Float)

/**
 * A single clip on the timeline.
 *
 * All durations/times are in microseconds. `startUs` is on the project timeline;
 * a clip's media is trimmed at sourceStartUs..sourceEndUs and played over its
 * timeline span. `durationUs` is the timeline duration (before speed scaling is
 * applied for the renderer, which derives the media-consume length from speed).
 */
@Serializable
data class TimelineClip(
    val id: String,
    val kind: ClipKind,
    val trackId: String,
    val sourceUri: String? = null,
    val sourceStartUs: Long = 0L,
    val sourceEndUs: Long = 0L,
    val startUs: Long,
    val durationUs: Long,
    val displayName: String = "Clip",
    val thumbnailUri: String? = null,
    // Rendering
    val speed: SpeedSettings = SpeedSettings(),
    val volumeEnvelope: VolumeEnvelope = VolumeEnvelope(),
    val colorGrade: ColorGrade = ColorGrade(),
    val grayscale: Boolean = false,
    val invert: Boolean = false,
    val blurRadius: Float = 0f,
    val chromaKey: ChromaKeySettings = ChromaKeySettings(),
    val transform: PiPTransform = PiPTransform(),
    val cornerPin: CornerPin = CornerPin(),
    val blendMode: BlendMode = BlendMode.NORMAL,
    val opacity: Float = 1f,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val transitionIn: TransitionKind = TransitionKind.NONE,
    val transitionOut: TransitionKind = TransitionKind.NONE,
    // Text overlays
    val text: String = "",
    val textColor: Long = -1, // ARGB
    val backgroundColor: Long = 0x00000000,
    val fontSizeSp: Float = 28f,
    val textPreset: TextPreset = TextPreset.NONE,
    // Animations = keyframed parameters on this clip
    val keyframedParams: Map<String, List<Keyframe>> = emptyMap(),
    // Audio
    val audioEffects: List<AudioEffectPreset> = emptyList(),
    val muted: Boolean = false,
) {
    val endUs: Long get() = startUs + durationUs
    val hasVisual: Boolean get() = kind == ClipKind.VIDEO || kind == ClipKind.IMAGE ||
        kind == ClipKind.TEXT || kind == ClipKind.PICTURE_OVERLAY
    val hasAudio: Boolean get() = kind == ClipKind.VIDEO || kind == ClipKind.AUDIO || kind == ClipKind.VOICEOVER

    /** Whether this clip is mostly no-op regarding effects, for preview optimization. */
    @Transient
    val needsTranscode: Boolean
        get() = speed.speed != 1f || !colorGrade.isNoOp || grayscale || invert || blurRadius > 0f ||
            chromaKey.enabled || !cornerPin.isIdentity || opacity < 1f || muted

    fun keyframesFor(parameter: String): List<Keyframe> = keyframedParams[parameter] ?: emptyList()

    fun keyframeValue(parameter: String, timeFraction: Float, default: Float = 1f): Float =
        KeyframedParameter(parameter, keyframesFor(parameter), default).valueAt(timeFraction)
}

/** A lane on the timeline. Overlay/text/audio lanes are independent layers. */
@Serializable
data class TimelineTrack(
    val id: String,
    val kind: TrackKind,
    val name: String,
    val clips: List<TimelineClip> = emptyList(),
    val locked: Boolean = false,
    val muted: Boolean = false,
    val visible: Boolean = true,
) {
    fun clip(id: String): TimelineClip? = clips.firstOrNull { it.id == id }
}

/** Export presets mirroring the resolution/codec options of mobile editors. */
@Serializable
data class ExportPreset(
    val label: String = "1080p 30fps",
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRateFps: Int = 30,
    val bitrateBps: Int? = null, // null = default
    val videoMimeType: String = "video/avc",
    val audioMimeType: String = "audio/mp4a-latm",
    val aspectRatioLabel: String = "16:9",
) {
    val is4K: Boolean get() = width >= 3840
    val isHighFrameRate: Boolean get() = frameRateFps >= 60
}

/**
 * The complete editable project: a primary video/audio timeline plus overlay,
 * text, image, voiceover and music layers. This is the modern replacement for the
 * flat Edit Decision List and is what the exporter consumes.
 */
@Serializable
data class ProjectTimeline(
    val projectId: String,
    val name: String = "Untitled project",
    val sourceUri: String? = null,
    val sourceDurationUs: Long = 0L,
    val aspectRatioLabel: String = "16:9",
    val outputWidth: Int = 1920,
    val outputHeight: Int = 1080,
    val frameRateFps: Int = 30,
    val tracks: List<TimelineTrack> = defaultTracks(),
    val exportPreset: ExportPreset = ExportPreset(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    val mainTrack: TimelineTrack?
        get() = tracks.firstOrNull { it.kind == TrackKind.VIDEO }

    val durationUs: Long
        get() = tracks.flatMap { it.clips }.maxOfOrNull { it.endUs } ?: sourceDurationUs

    val hasAudioClips: Boolean
        get() = tracks.any { it.muted.not() }.let { _ ->
            tracks.flatMap { it.clips }.any { it.hasAudio && it.muted.not() }
        }

    fun allClips(): List<TimelineClip> = tracks.flatMap { it.clips }

    fun clip(id: String): TimelineClip? = allClips().firstOrNull { it.id == id }

    /** The topmost visual clip under a timeline position (primary first, then picks). */
    fun clipAt(positionUs: Long): TimelineClip? {
        for (clip in allClips().filter { it.hasVisual }.sortedBy { it.startUs }) {
            if (positionUs in clip.startUs until clip.endUs) return clip
        }
        return null
    }

    fun withTrack(track: TimelineTrack): ProjectTimeline =
        copy(tracks = tracks.map { if (it.id == track.id) track else it }, updatedAtEpochMs = System.currentTimeMillis())

    fun addTrack(kind: TrackKind): ProjectTimeline {
        val id = newId(kind.name)
        return copy(
            tracks = tracks + TimelineTrack(id = id, kind = kind, name = kind.name.lowercase().replaceFirstChar { it.uppercase() }),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    fun removeTrack(trackId: String): ProjectTimeline {
        if (tracks.count { it.kind == TrackKind.VIDEO } <= 1 && trackId == mainTrack?.id) return this
        return copy(tracks = tracks.filterNot { it.id == trackId }, updatedAtEpochMs = System.currentTimeMillis())
    }

    fun upsertClip(clip: TimelineClip): ProjectTimeline {
        val updated = tracks.map { track ->
            if (track.id != clip.trackId) track
            else track.copy(clips = track.clips.filterNot { it.id == clip.id } + clip)
        }
        return copy(tracks = updated, updatedAtEpochMs = System.currentTimeMillis())
    }

    fun removeClip(clipId: String): ProjectTimeline {
        val updated = tracks.map { track -> track.copy(clips = track.clips.filterNot { it.id == clipId }) }
        return copy(tracks = updated, updatedAtEpochMs = System.currentTimeMillis())
    }

    fun moveClip(clipId: String, newStartUs: Long): ProjectTimeline {
        val clip = clip(clipId) ?: return this
        return upsertClip(clip.copy(startUs = newStartUs.coerceAtLeast(0L)))
    }

    fun trimClip(clipId: String, newDurationUs: Long): ProjectTimeline {
        val clip = clip(clipId) ?: return this
        val safe = newDurationUs.coerceAtLeast(1L)
        return upsertClip(clip.copy(durationUs = safe))
    }

    fun toggleTrackLock(trackId: String): ProjectTimeline =
        withTrack(tracks.first { it.id == trackId }.let { it.copy(locked = !it.locked) })

    fun toggleTrackMute(trackId: String): ProjectTimeline =
        withTrack(tracks.first { it.id == trackId }.let { it.copy(muted = !it.muted) })

    private fun newId(prefix: String): String =
        "$prefix-${java.util.UUID.randomUUID().toString().take(6)}"

    companion object {
        fun defaultTracks(): List<TimelineTrack> = listOf(
            TimelineTrack(id = "track-video", kind = TrackKind.VIDEO, name = "Video"),
            TimelineTrack(id = "track-overlay", kind = TrackKind.OVERLAY, name = "Overlay"),
            TimelineTrack(id = "track-voiceover", kind = TrackKind.VOICEOVER, name = "Voiceover"),
            TimelineTrack(id = "track-music", kind = TrackKind.MUSIC, name = "Music"),
        )
    }
}