package com.projecthub.clearcut.core.scanner

import com.projecthub.clearcut.core.model.AnalysisCoverage
import com.projecthub.clearcut.core.model.AnalysisMethod
import com.projecthub.clearcut.core.model.AnalysisTrack
import com.projecthub.clearcut.core.model.CoverageSegment
import com.projecthub.clearcut.core.model.Evidence
import com.projecthub.clearcut.core.model.Finding
import com.projecthub.clearcut.core.model.FindingCategory
import com.projecthub.clearcut.core.model.FindingStatus
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.ProposedEdit
import com.projecthub.clearcut.core.model.ProposedOperation
import com.projecthub.clearcut.core.model.SamplingIntensity
import com.projecthub.clearcut.core.model.ScanReport
import com.projecthub.clearcut.core.model.ScanSettingsSnapshot
import com.projecthub.clearcut.core.model.TimeRange
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.UUID

/**
 * Coordinator that merges the three database-free analysis methods into one
 * timestamped [ScanReport].
 *
 * The degradation strategy:
 *  1. Audio fingerprints (PCM chunks analyzed by [AudioAnalyzer]).
 *  2. Visual hashes (downscaled frames analyzed by [VisualAnalyzer]).
 *  3. Structural/signal analysis ([StructuralAnalyzer]).
 *
 * Any method that cannot decode or run fully yields explicit [FindingStatus.SUSPECTED]
 * or coverage gaps rather than silent success.
 */
class MultiMethodScanner {

    data class DecodedAudio(val pcm: FloatArray, val sampleRate: Int)
    data class DecodedFrame(val pixels: ByteArray, val width: Int, val height: Int, val timestampUs: Long, val lumaMean: Float)

    /** Provide decoded audio (all decodable channels) or null when unavailable. */
    var audioProvider: (suspend (MediaInfo, ScanSettingsSnapshot, suspend (Float) -> Unit) -> DecodedAudio?)? = null

    /** Provide sampled decoded frames (grayscale) or null when unavailable. */
    var frameProvider: (suspend (MediaInfo, ScanSettingsSnapshot, suspend (Float) -> Unit) -> List<DecodedFrame>?)? = null

    suspend fun scan(media: MediaInfo, settings: ScanSettingsSnapshot): ScanReport {
        val warnings = mutableListOf<String>()
        val limitations = mutableListOf<String>()
        val coverageWindows = mutableListOf<TimeRange>()
        val allFindings = mutableListOf<Finding>()
        val analyzedWindowsAudio = mutableListOf<TimeRange>()
        val analyzedWindowsVisual = mutableListOf<TimeRange>()
        var partialDecode = false

        val frames: List<DecodedFrame>?
        val decodedAudio: DecodedAudio?

        coroutineScope {
            val audioDeferred = async {
                if (settings.compareAudio && media.hasAudio && audioProvider != null) {
                    runCatching {
                        audioProvider!!(media, settings) { /* progress */ }
                    }.getOrNull()
                } else null
            }
            val framesDeferred = async {
                if (settings.compareVisual && media.hasVideo && frameProvider != null) {
                    runCatching {
                        frameProvider!!(media, settings) { /* progress */ }
                    }.getOrNull()
                } else null
            }

            decodedAudio = audioDeferred.await()
            frames = framesDeferred.await()
            decodedAudioWasUnavailable = settings.compareAudio && media.hasAudio && decodedAudio == null

            // ---- Method A: audio self-similarity ----
            if (decodedAudio != null) {
                try {
                    val analyzer = AudioAnalyzer(
                        sampleRate = decodedAudio.sampleRate,
                        similarThreshold = 0.92f,
                        repeatedThreshold = 0.97f,
                    )
                    val fps = analyzer.fingerprintChunks(
                        decodedAudio.pcm,
                        chunkSize = settings.sampling.audioChunk,
                        hopSize = settings.sampling.audioChunk / 2,
                    )
                    if (fps.isEmpty()) {
                        limitations.add("Audio decoding produced no usable samples; audio analysis skipped.")
                    } else {
                        val matches = analyzer.findSelfSimilarities(fps)
                        val audioDurUs = media.durationUs
                        analyzedWindowsAudio.add(TimeRange(0, audioDurUs))
                        val repeated = matches.filter { it.similarity >= 0.97f }
                        val similar = matches.filter { it.similarity >= 0.92f && it.similarity < 0.97f }
                        findSelfAudioFindings(repeated, media, allFindings, FindingCategory.REPEATED_AUDIO, audioDurUs, settings, "Repeated audio")
                        findSelfAudioFindings(similar, media, allFindings, FindingCategory.SIMILAR_AUDIO, audioDurUs, settings, "Similar audio")
                    }
                } catch (t: Throwable) {
                    warnings.add("Audio similarity analysis failed: ${t.message}")
                    limitations.add("Audio similarity analysis could not run to completion.")
                }
            } else if (settings.compareAudio && media.hasAudio) {
                limitations.add("Audio track could not be decoded; audio analysis skipped.")
                warnings.add("Audio track could not be decoded.")
                partialDecode = true
            }

            // ---- Method B + C: visual and structural ----
            if (frames != null && frames.isNotEmpty() && media.hasVideo) {
                try {
                    val visual = VisualAnalyzer()
                    val hashes = frames.map {
                        visual.hashFrame(it.pixels, it.width, it.height, it.pixels.hashCode(), it.timestampUs)
                    }
                    val (repeated, similar) = visual.repeatedVsSimilar(hashes)
                    val videoDurUs = maxOf(media.durationUs, frames.maxOf { it.timestampUs })
                    analyzedWindowsVisual.add(TimeRange(0, videoDurUs))
                    addVisualFindings(repeated, media, allFindings, FindingCategory.REPEATED_VISUALS, settings)
                    addVisualFindings(similar, media, allFindings, FindingCategory.SIMILAR_VISUALS, settings)

                    // Structural: scene changes from frame luma
                    val luma = frames.map { it.lumaMean / 255f }
                    val frameTs = frames.map { it.timestampUs }
                    val structural = StructuralAnalyzer()
                    val scenes = structural.detectSceneChanges(frameTs, luma)
                    val segmentSignals = StructuralAnalyzer.buildSegmentSignals(
                        durationsUs = frames.zipWithNext { a, b -> b.timestampUs - a.timestampUs }.map { it.coerceAtLeast(1) },
                        lumaMeans = luma,
                        audioLevels = if (decodedAudio != null) List(luma.size) { 0.5f } else List(luma.size) { 0f },
                    )
                    val disconn = structural.detectAudioDiscontinuities(segmentSignals)
                    val patterns = structural.detectPatterns(segmentSignals)

                    scenes.forEach { e ->
                        allFindings.add(
                            structuralFinding(e.timestampUs, e.timestampUs + 500_000, e.type == StructuralAnalyzer.EventType.SCENE_BOUNDARY, e.intensity, settings, e.detail)
                        )
                    }
                    disconn.forEach { e ->
                        allFindings.add(
                            Finding(
                                id = newId(), category = FindingCategory.STRUCTURAL_ANOMALY, status = FindingStatus.SUSPECTED,
                                track = AnalysisTrack.STRUCTURAL, method = AnalysisMethod.STRUCTURAL,
                                range = TimeRange(e.timestampUs, e.timestampUs + 500_000), confidence = e.intensity,
                                confidenceExplanation = "Audio discontinuity detected from statistical deviation.",
                                evidence = listOf(Evidence(e.detail)),
                                note = "Technical observation; not a copyright determination.",
                            )
                        )
                    }
                    patterns.forEach { e ->
                        allFindings.add(
                            Finding(
                                id = newId(), category = FindingCategory.STRUCTURAL_ANOMALY, status = FindingStatus.SUSPECTED,
                                track = AnalysisTrack.STRUCTURAL, method = AnalysisMethod.STRUCTURAL,
                                range = TimeRange(e.timestampUs, e.timestampUs + 500_000), confidence = e.intensity,
                                confidenceExplanation = "Repeated temporal signal pattern detected.",
                                evidence = listOf(Evidence(e.detail)),
                                note = "Technical observation; not a copyright determination.",
                            )
                        )
                    }
                } catch (t: Throwable) {
                    warnings.add("Visual/structural analysis failed: ${t.message}")
                    limitations.add("Visual and structural analysis could not run to completion.")
                    partialDecode = true
                }
            } else if (settings.compareVisual && media.hasVideo) {
                limitations.add("Video frames could not be decoded; visual analysis skipped.")
                warnings.add("Video frames could not be decoded.")
                partialDecode = true
            }
        }

        // Merge coverage
        val analyzedAudio = analyzedWindowsAudio.map { CoverageSegment(it, analyzed = true) }
        val analyzedVideo = analyzedWindowsVisual.map { CoverageSegment(it, analyzed = true) }
        val combined = analyzedAudio + analyzedVideo

        // Build unanalyzed ranges
        val analyzedMerged = com.projecthub.clearcut.core.media.TimestampMapper.mergeIntervals(
            combined.map { it.range },
        )
        val unanalyzed = computeUnanalyzed(media.durationUs, analyzedMerged)

        val coverage = AnalysisCoverage(
            mediaDurationUs = media.durationUs,
            analyzedSegments = combined,
            unanalyzedRanges = unanalyzed,
            droppedTracks = buildList {
                if (settings.compareAudio && media.hasAudio && decodedAudioWasUnavailable) add("audio (decode failed)")
                if (settings.compareVisual && media.hasVideo && frames == null) add("video (decode failed)")
            },
            partialDecode = partialDecode || unanalyzed.isNotEmpty(),
        )

        val methodsUsed = buildList {
            add(AnalysisMethod.AUDIO_FINGERPRINT)
            add(AnalysisMethod.VISUAL_HASH)
            add(AnalysisMethod.STRUCTURAL)
        }

        val proposedEdits = buildProposedEdits(allFindings)

        // Filter below min confidence
        val filtered = allFindings.filter { it.confidence >= settings.minConfidence }

        return ScanReport(
            mediaId = media.uri,
            mediaName = media.displayName,
            scannedAtEpochMs = System.currentTimeMillis(),
            mediaDurationUs = media.durationUs,
            methodsUsed = methodsUsed,
            coverage = coverage,
            findings = filtered,
            proposedEdits = proposedEdits,
            warnings = warnings.distinct(),
            limitations = limitations.distinct(),
            settings = settings,
        )
    }

    private var decodedAudioWasUnavailable = false

    private fun findSelfAudioFindings(
        matches: List<AudioAnalyzer.Match>,
        media: MediaInfo,
        out: MutableList<Finding>,
        category: FindingCategory,
        durationUs: Long,
        settings: ScanSettingsSnapshot,
        label: String,
    ) {
        matches.forEach { m ->
            val start = m.firstRange.startUs.coerceIn(0, durationUs)
            val end = m.firstRange.endUs.coerceIn(start, durationUs)
            if (end - start < 500_000) return@forEach
            out.add(
                Finding(
                    id = newId(),
                    category = category,
                    status = if (m.similarity >= 0.97f) FindingStatus.CONFIRMED else FindingStatus.SUSPECTED,
                    track = AnalysisTrack.AUDIO,
                    method = AnalysisMethod.AUDIO_FINGERPRINT,
                    range = TimeRange(start, end),
                    confidence = m.similarity,
                    confidenceExplanation = "Audio fingerprint similarity to another passage in the same file at ${TimeRange.formatUs(m.secondRange.startUs)} (score ${m.similarity})",
                    evidence = buildList {
                        add(Evidence("Audio fingerprint match", m.similarity.toDouble(), "score"))
                        add(Evidence("Matching passage starts at", m.secondRange.startUs.toDouble(), "μs"))
                    },
                    note = "$label detected via database-free self-comparison.",
                )
            )
        }
    }

    private fun addVisualFindings(
        matches: List<VisualAnalyzer.Match>,
        media: MediaInfo,
        out: MutableList<Finding>,
        category: FindingCategory,
        settings: ScanSettingsSnapshot,
    ) {
        matches.forEach { m ->
            val start = m.firstRange.startUs.coerceIn(0, media.durationUs)
            val end = m.firstRange.endUs.coerceIn(start, media.durationUs)
            if (end - start < 250_000) return@forEach
            out.add(
                Finding(
                    id = newId(),
                    category = category,
                    status = if (m.hamming <= 4) FindingStatus.CONFIRMED else FindingStatus.SUSPECTED,
                    track = AnalysisTrack.VIDEO,
                    method = AnalysisMethod.VISUAL_HASH,
                    range = TimeRange(start, end),
                    confidence = m.similarity,
                    confidenceExplanation = "Perceptual frame hash difference of ${m.hamming} bits vs frame at ${TimeRange.formatUs(m.secondRange.startUs)}.",
                    evidence = listOf(Evidence("dHash Hamming distance", m.hamming.toDouble(), "bits")),
                    note = "Visual similarity is not proof of infringement or ownership.",
                )
            )
        }
    }

    private fun structuralFinding(
        startUs: Long,
        endUs: Long,
        isSceneBoundary: Boolean,
        intensity: Float,
        settings: ScanSettingsSnapshot,
        detail: String,
    ): Finding = Finding(
        id = newId(),
        category = FindingCategory.STRUCTURAL_ANOMALY,
        status = if (isSceneBoundary) FindingStatus.CONFIRMED else FindingStatus.SUSPECTED,
        track = AnalysisTrack.STRUCTURAL,
        method = AnalysisMethod.STRUCTURAL,
        range = TimeRange(startUs, endUs),
        confidence = intensity,
        confidenceExplanation = if (isSceneBoundary) "Scene boundary detected via luma statistics." else "Abrupt visual transition detected.",
        evidence = listOf(Evidence(detail)),
        note = "Technical observation; not a copyright determination.",
    )

    private fun buildProposedEdits(findings: List<Finding>): List<ProposedEdit> {
        return findings
            .filter { it.category == FindingCategory.REPEATED_AUDIO || it.category == FindingCategory.REPEATED_VISUALS }
            .sortedBy { it.startUs }
            .map { f ->
                ProposedEdit(
                    id = newId(),
                    range = f.range,
                    track = f.track,
                    findingIds = listOf(f.id),
                    operation = if (f.track == AnalysisTrack.AUDIO) ProposedOperation.REMOVE_AUDIO else ProposedOperation.REMOVE_AUDIO_VIDEO,
                )
            }
    }

    private fun computeUnanalyzed(durationUs: Long, analyzed: List<TimeRange>): List<TimeRange> {
        if (durationUs <= 0L) return emptyList()
        val merged = analyzed.sortedBy { it.startUs }.filter { it.durationUs > 0 }
        val gaps = mutableListOf<TimeRange>()
        var cursor = 0L
        for (r in merged) {
            if (r.startUs > cursor) gaps.add(TimeRange(cursor, r.startUs))
            cursor = maxOf(cursor, r.endUs)
        }
        if (cursor < durationUs) gaps.add(TimeRange(cursor, durationUs))
        return gaps
    }

    private fun newId(): String = UUID.randomUUID().toString().take(8)
}