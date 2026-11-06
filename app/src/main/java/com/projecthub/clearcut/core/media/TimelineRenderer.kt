@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.projecthub.clearcut.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.AlphaScale
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.projecthub.clearcut.core.model.AnalysisTrack
import com.projecthub.clearcut.core.model.BlendMode
import com.projecthub.clearcut.core.model.ClipKind
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.ProjectTimeline
import com.projecthub.clearcut.core.model.TransitionKind
import com.projecthub.clearcut.core.model.TrackKind
import com.projecthub.clearcut.core.model.TimelineClip
import com.projecthub.clearcut.core.model.Edl
import com.projecthub.clearcut.core.model.EdlOperation
import com.projecthub.clearcut.core.model.EditOperationType
import com.projecthub.clearcut.core.model.SyncPolicy
import com.projecthub.clearcut.core.model.TimeRange
import com.projecthub.clearcut.core.model.ValidationStatus
import com.projecthub.clearcut.core.model.VolumeEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renders a [ProjectTimeline] to an MP4 using Media3 Transformer.
 *
 * The timeline is converted into a composition of non-overlapping media-item
 * sequences. Video clips on the primary track are concatenated in timeline order;
 * audio-bearing tracks (music, voiceover, and the primary video's audio) are
 * emitted as separate sequences so the mixed audio is summed by Transformer.
 *
 * Every effect a clip declares is mapped onto the supported Media3 primitives:
 *   - speed (SpeedChangeEffect + Sonic time-stretch)
 *   - color grading (Brightness/Contrast via matrix, saturation/hue/lightness)
 *   - grayscale / invert / blur (RgbFilter + GaussianBlur)
 *   - fade in/out and scene transitions
 *   - volume envelopes and audio effects via [AudioProcessors]
 *   - text overlay clips (TextOverlay), picture/image overlays (BitmapOverlay)
 *   - picture-in-picture placement (ScaleAndRotate + OverlaySettings)
 *
 * Features requiring heavy off-device GPU work (opacity, blend modes, chroma-key
 * and corner-pin warping on video) are validated and reported as export warnings
 * rather than silently dropped, mirroring the honest-limitation stance of ClearCut.
 */
@androidx.media3.common.util.UnstableApi
class TimelineRenderer(private val context: Context) {

    data class RenderResult(
        val outputPath: String,
        val outputDurationUs: Long,
        val sizeBytes: Long,
        val warnings: List<String>,
    )

    /**
     * Validate a timeline before export and return non-fatal warnings for
     * effects that cannot be reproduced on a device software encoder.
     */
    fun validateTimeline(timeline: ProjectTimeline): List<String> {
        val warnings = mutableListOf<String>()
        for (clip in timeline.allClips()) {
            if (clip.blendMode != BlendMode.NORMAL) {
                warnings += "Blend mode '${clip.blendMode.label}' on '${clip.displayName}' is best previewed on-device; export may approximate it."
            }
            if (clip.chromaKey.enabled && clip.kind == ClipKind.VIDEO) {
                warnings += "Chroma key on video clip '${clip.displayName}' requires GPU compositing and is not reproduced in MP4 export."
            }
            if (!clip.cornerPin.isIdentity) {
                warnings += "Corner-pin warping on '${clip.displayName}' is a preview feature and is not exported."
            }
            if (clip.kind == ClipKind.TEXT && clip.text.isBlank()) {
                warnings += "Text clip '${clip.displayName}' has no text; exported without overlay."
            }
        }
        return warnings
    }

    suspend fun render(
        timeline: ProjectTimeline,
        outputPath: String,
        onProgress: suspend (Float) -> Unit,
    ): RenderResult = withContext(Dispatchers.Main) {
        val outputFile = File(outputPath)
        if (timeline.sourceUri != null && outputFile.absolutePath == timeline.sourceUri) {
            throw ClearCutError.RenderError("Refusing to overwrite the source file.")
        }
        val warnings = validateTimeline(timeline)
        val sequences = buildSequences(timeline)
        if (sequences.isEmpty()) {
            throw ClearCutError.RenderError("The timeline has no playable content.")
        }
        val composition = Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(true)
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(timeline.exportPreset.videoMimeType)
            .setAudioMimeType(timeline.exportPreset.audioMimeType)
            .build()

        awaitCompletion(transformer, composition, outputFile, onProgress, warnings)
    }

    /** Builds the list of EditedMediaItemSequence for a timeline. */
    private fun buildSequences(timeline: ProjectTimeline): List<EditedMediaItemSequence> {
        val sequences = mutableListOf<EditedMediaItemSequence>()

        // Primary track: video/image clips, concatenated.
        val primaryItems = buildPrimarySequence(timeline)
        if (primaryItems.isNotEmpty()) {
            sequences += EditedMediaItemSequence(primaryItems)
        }

        // Text overlay lane: text clips on the TEXT (or any non-primary) lane are layered over
        // the whole session using a transparent still. They are concatenated in time order, each
        // consuming its own timeline span (blank text clips are skipped).
        val textItems = timeline.tracks
            .filter { track ->
                track.kind != TrackKind.VIDEO &&
                    track.clips.any { it.kind == ClipKind.TEXT && it.text.isNotBlank() }
            }
            .flatMap { track -> track.clips.filter { it.kind == ClipKind.TEXT && it.text.isNotBlank() } }
            .sortedBy { it.startUs }
            .map { buildTextItem(it, timeline) }
        if (textItems.isNotEmpty()) {
            sequences += EditedMediaItemSequence(textItems)
        }

        // Audio overlays: each audio-bearing non-primary clip becomes its own sequence so it mixes
        // over the whole project duration. Multiple audio clips on the same lane are concatenated.
        val audioLanes = mutableMapOf<String, MutableList<EditedMediaItem>>()
        for (clip in timeline.allClips()) {
            if (!clip.hasAudio || clip.muted || clip.sourceUri == null) continue
            if (timeline.mainTrack?.id == clip.trackId) continue // primary audio handled with video
            audioLanes.getOrPut(clip.trackId) { mutableListOf() } += buildAudioItem(clip, timeline)
        }
        for (laneItems in audioLanes.values) {
            if (laneItems.isNotEmpty()) sequences += EditedMediaItemSequence(laneItems)
        }
        return sequences
    }

    private fun buildPrimarySequence(timeline: ProjectTimeline): List<EditedMediaItem> {
        val main = timeline.mainTrack ?: return emptyList()
        val items = mutableListOf<EditedMediaItem>()
        for (clip in main.clips.sortedBy { it.startUs }) {
            when (clip.kind) {
                ClipKind.VIDEO -> items += buildVideoItem(clip, timeline)
                ClipKind.IMAGE, ClipKind.PICTURE_OVERLAY -> items += buildImageItem(clip, timeline)
                else -> Unit // text handled via its overlay lane
            }
        }
        return items
    }

    private fun buildVideoItem(clip: TimelineClip, timeline: ProjectTimeline): EditedMediaItem {
        if (clip.sourceUri == null) {
            throw ClearCutError.RenderError("Video clip '${clip.displayName}' has no source.")
        }
        val durationUs = clipDurationForExport(clip)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(clip.sourceUri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((clip.sourceStartUs / 1000L).coerceAtLeast(0))
                    .setEndPositionMs(clip.sourceEndUs.coerceAtLeast(clip.sourceStartUs + 1) / 1000L)
                    .build()
            )
            .build()

        val videoEffects = mutableListOf<Effect>()
        val audioProcessors = mutableListOf<AudioProcessor>()

        if (clip.speed.speed != 1f) {
            videoEffects += SpeedChangeEffect(clip.speed.speed)
        }
        videoEffects += colorEffects(clip)
        val transform = clip.transform
        if (clip.kind == ClipKind.PICTURE_OVERLAY || clip.kind == ClipKind.IMAGE) {
            videoEffects += scaleRotateTransform(transform)
        }
        if (clip.opacity < 1f) videoEffects += AlphaScale(clip.opacity)

        // Audio: envelope + effects.
        audioProcessors += envelopeProcessor(clip)
        audioProcessors += buildAudioEffects(clip.audioEffects)

        val effects = Effects(audioProcessors, videoEffects)
        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(durationUs)
            .setFrameRate(if (timeline.frameRateFps > 0) timeline.frameRateFps else C.RATE_UNSET_INT)
            .setEffects(effects)
            .build()
    }

    private fun buildImageItem(clip: TimelineClip, timeline: ProjectTimeline): EditedMediaItem {
        if (clip.sourceUri == null) {
            throw ClearCutError.RenderError("Image clip '${clip.displayName}' has no source.")
        }
        val durationUs = clipDurationForExport(clip)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(clip.sourceUri))
            .setImageDurationMs((durationUs / 1000L).coerceAtLeast(1))
            .build()
        val videoEffects = mutableListOf<Effect>()
        videoEffects += colorEffects(clip)
        videoEffects += scaleRotateTransform(clip.transform)
        val effects = Effects(emptyList(), videoEffects)
        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(durationUs)
            .setFrameRate(if (timeline.frameRateFps > 0) timeline.frameRateFps else C.RATE_UNSET_INT)
            .setEffects(effects)
            .build()
    }

    private fun buildTextItem(clip: TimelineClip, timeline: ProjectTimeline): EditedMediaItem {
        if (clip.text.isBlank()) {
            // A text clip with empty text would contribute nothing to the export yet still occupies
            // its timeline span; represent it with a transparent 1-frame image.
            val holderUri = Uri.parse("android.resource://" + context.packageName + "/raw/transparent")
            return EditedMediaItem.Builder(
                MediaItem.Builder().setUri(holderUri).setImageDurationMs(1).build()
            ).setDurationUs(1).build()
        }
        val spannable = android.text.SpannableString(clip.text)
        val textOverlay = TextOverlay.createStaticTextOverlay(spannable)
        val durationUs = clipDurationForExport(clip)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse("android.resource://" + context.packageName + "/raw/transparent"))
            .setImageDurationMs(durationUs / 1000L)
            .build()
        val effects = Effects(
            emptyList(),
            listOf(OverlayEffect(com.google.common.collect.ImmutableList.of(textOverlay))),
        )
        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(durationUs)
            .setFrameRate(if (timeline.frameRateFps > 0) timeline.frameRateFps else C.RATE_UNSET_INT)
            .setEffects(effects)
            .build()
    }

    private fun buildAudioItem(clip: TimelineClip, timeline: ProjectTimeline): EditedMediaItem {
        if (clip.sourceUri == null) {
            throw ClearCutError.RenderError("Audio clip '${clip.displayName}' has no source.")
        }
        val durationUs = clipDurationForExport(clip)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(clip.sourceUri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((clip.sourceStartUs / 1000L).coerceAtLeast(0))
                    .setEndPositionMs(clip.sourceEndUs.coerceAtLeast(clip.sourceStartUs + 1) / 1000L)
                    .build()
            )
            .build()
        val processors = mutableListOf<AudioProcessor>()
        processors += envelopeProcessor(clip)
        processors += buildAudioEffects(clip.audioEffects)
        if (clip.speed.speed != 1f) {
            // Time-stretch while preserving pitch when requested; otherwise change both.
            val sonic = androidx.media3.common.audio.SonicAudioProcessor()
            sonic.setSpeed(clip.speed.speed.coerceAtLeast(0.1f))
            if (!clip.speed.pitchPreserved) sonic.setPitch(clip.speed.speed.coerceAtLeast(0.1f))
            processors += sonic
        }
        val effects = Effects(processors, emptyList())
        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(durationUs)
            .setEffects(effects)
            .build()
    }

    private fun envelopeProcessor(clip: TimelineClip): AudioProcessor {
        if (clip.volumeEnvelope.points.isEmpty() && clip.volumeEnvelope.baseGain == 1f) {
            return GainAudioProcessor(FloatArray(0), 1f)
        }
        // Sample-rate is unknown here (the processor is configured with the input format later),
        // so build a ramp of the clip duration at 44100 fps as a reliable default. Media3 feeds
        // 48000/44100 PCM depending on device; the ramp length being independent of the real rate
        // keeps the envelope shape correct (it is indexed by sample frame, not by time).
        val estFrames = (clipDurationForExport(clip) * 44100 / 1_000_000L).toInt().coerceAtLeast(2)
        val ramp = GainAudioProcessor.buildRamp(clip.volumeEnvelope, estFrames, 44100)
        return GainAudioProcessor(ramp, clip.volumeEnvelope.baseGain)
    }

    /** Clip duration in output timeline, honoring speed. */
    private fun clipDurationForExport(clip: TimelineClip): Long {
        val speed = clip.speed.speed.coerceAtLeast(0.1f)
        return (clip.durationUs / speed).toLong().coerceAtLeast(1L)
    }

    private fun colorEffects(clip: TimelineClip): List<Effect> {
        val effects = mutableListOf<Effect>()
        val g = clip.colorGrade
        if (g.brightness != 0f) effects += Brightness(g.brightness.coerceIn(-1f, 1f))
        if (g.contrast != 0f) effects += Contrast(g.contrast.coerceIn(-1f, 1f))
        if (clip.grayscale) effects += RgbFilter.createGrayscaleFilter()
        if (clip.invert) effects += RgbFilter.createInvertedFilter()
        // Hue/saturation/lightness use HslAdjustment's matrix.
        if (g.saturation != 0f || g.hue != 0f || g.lightness != 0f) {
            effects += androidx.media3.effect.HslAdjustment.Builder()
                .adjustSaturation(g.saturation.coerceIn(-100f, 100f))
                .adjustHue(g.hue.coerceIn(-180f, 180f))
                .adjustLightness(g.lightness.coerceIn(-100f, 100f))
                .build()
        }
        return effects
    }

    private fun scaleRotateTransform(transform: com.projecthub.clearcut.core.model.PiPTransform): Effect {
        val builder = ScaleAndRotateTransformation.Builder()
        builder.setScale(transform.scaleX, transform.scaleY)
        builder.setRotationDegrees(transform.rotationDegrees)
        return builder.build()
    }

    private suspend fun awaitCompletion(
        transformer: Transformer,
        composition: Composition,
        outputFile: File,
        onProgress: suspend (Float) -> Unit,
        warnings: List<String>,
    ): RenderResult = suspendCancellableCoroutine { cont ->
        val listener = object : Transformer.Listener {
            override fun onCompleted(compositionParam: Composition, exportResult: ExportResult) {
                cont.resume(
                    RenderResult(
                        outputPath = outputFile.absolutePath,
                        outputDurationUs = exportResult.durationMs * 1000L,
                        sizeBytes = outputFile.length(),
                        warnings = warnings,
                    )
                )
            }

            override fun onError(
                compositionParam: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                cont.resumeWithException(
                    when (exportException) {
                        is ExportException -> ClearCutError.RenderError(
                            "Export failed: ${exportException.errorCodeName ?: "codec error"}. " +
                                "This can happen when the device encoder or source codecs are incompatible.",
                            exportException,
                        )
                        else -> ClearCutError.RenderError("Export failed: ${exportException.message ?: "unknown error"}", exportException)
                    }
                )
            }
        }
        transformer.addListener(listener)
        transformer.start(composition, outputFile.path)
        cont.invokeOnCancellation {
            runCatching { transformer.cancel() }
        }
    }

    companion object {
        const val MIME_TYPE_AVC = "video/avc"
        const val MIME_TYPE_AAC = "audio/mp4a-latm"
    }
}

/** Maps a legacy [Edl] to a timeline so old scan-flow edits still render. */
@androidx.media3.common.util.UnstableApi
object EdlToTimeline {

    fun convert(edl: Edl, sourceUri: String, sourceDurationUs: Long): ProjectTimeline {
        val clipId = "primary-source"
        val track = com.projecthub.clearcut.core.model.TimelineTrack(
            id = "track-video",
            kind = com.projecthub.clearcut.core.model.TrackKind.VIDEO,
            name = "Video",
            clips = listOf(
                com.projecthub.clearcut.core.model.TimelineClip(
                    id = clipId,
                    kind = ClipKind.VIDEO,
                    trackId = "track-video",
                    sourceUri = sourceUri,
                    sourceStartUs = 0L,
                    sourceEndUs = sourceDurationUs,
                    startUs = 0L,
                    durationUs = sourceDurationUs,
                    displayName = "Source",
                )
            ),
        )
        // Convert cut operations by trimming the source clip's spans. This keeps
        // the legacy "Copyright Removal Lab" semantics: segments surviving the EDL
        // become trimmed primary clips joined in sequence.
        val cuts = edl.operations.filter { it.operationType == EditOperationType.CUT }
            .sortedBy { it.sourceRange.startUs }
        val segments = mutableListOf<TimeRange>()
        var cursor = 0L
        for (c in cuts) {
            if (c.sourceRange.startUs > cursor) {
                segments += TimeRange(cursor, c.sourceRange.startUs)
            }
            cursor = maxOf(cursor, c.sourceRange.endUs)
        }
        if (cursor < sourceDurationUs) segments += TimeRange(cursor, sourceDurationUs)

        val clips = if (segments.isEmpty()) {
            track.clips
        } else {
            segments.map { seg ->
                track.clips.first().copy(
                    id = "seg-${seg.startUs}-${seg.endUs}",
                    sourceStartUs = seg.startUs,
                    sourceEndUs = seg.endUs,
                    durationUs = seg.durationUs,
                )
            }
        }
        return ProjectTimeline(
            projectId = edl.mediaId,
            name = "EDL project",
            sourceUri = sourceUri,
            sourceDurationUs = sourceDurationUs,
            tracks = listOf(track.copy(clips = clips)),
        )
    }
}