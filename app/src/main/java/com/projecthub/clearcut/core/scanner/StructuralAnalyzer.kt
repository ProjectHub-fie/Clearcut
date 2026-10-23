package com.projecthub.clearcut.core.scanner

import com.projecthub.clearcut.core.model.TimeRange
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Structural/signal analysis over decoded media: scene boundaries, repeated frames,
 * abrupt transitions, audio discontinuities, potential edit boundaries and repeated
 * temporal patterns.
 *
 * These are technical observations, not definitive copyright matches. Pure Kotlin.
 */
class StructuralAnalyzer {

    data class SceneEvent(
        val timestampUs: Long,
        val type: EventType,
        val intensity: Float,
        val detail: String,
    )

    data class SegmentSignal(
        val startUs: Long,
        val endUs: Long,
        val averageLuma: Float,
        val frameDelta: Float,   // mean inter-frame difference
        val audioLevel: Float,   // 0..1
        val audioJitter: Float,  // discontinuity measure
    )

    enum class EventType { SCENE_BOUNDARY, REPEATED_FRAME, ABRUPT_TRANSITION, AUDIO_DISCONTINUITY, PATTERN }

    /**
     * Scene boundaries + abrupt transitions from per-frame luma values.
     * [frameTimestampsUs] must pair 1:1 with [frameLuma].
     */
    fun detectSceneChanges(
        frameTimestampsUs: List<Long>,
        frameLuma: List<Float>,
    ): List<SceneEvent> {
        if (frameTimestampsUs.size < 3) return emptyList()
        val result = mutableListOf<SceneEvent>()
        // Global mean + std of inter-frame deltas.
        val deltas = (1 until frameLuma.size).map { abs(frameLuma[it] - frameLuma[it - 1]) }
        val mean = deltas.average().toFloat()
        val variance = deltas.map { (it - mean) * (it - mean) }.average()
        val std = sqrt(variance).toFloat().coerceAtLeast(1e-4f)
        for (i in 1 until frameLuma.size) {
            val d = abs(frameLuma[i] - frameLuma[i - 1])
            if (d > mean + 3.0f * std) {
                result.add(
                    SceneEvent(
                        timestampUs = frameTimestampsUs[i],
                        type = EventType.SCENE_BOUNDARY,
                        intensity = (d / (mean + std)).coerceIn(0f, 1f),
                        detail = "Large frame difference (luma Δ scaled x%.1f)".format(d / mean),
                    )
                )
            } else if (d > mean + 1.8f * std) {
                result.add(
                    SceneEvent(
                        timestampUs = frameTimestampsUs[i],
                        type = EventType.ABRUPT_TRANSITION,
                        intensity = (d / (mean + std)).coerceIn(0f, 1f),
                        detail = "Abrupt transition (luma Δ scaled x%.1f)".format(d / mean),
                    )
                )
            }
        }
        return result
    }

    /** Repeated frames = frames within a small neighborhood sharing near-identical luma and delta signature. */
    fun detectRepeatedFrames(
        frameTimestampsUs: List<Long>,
        frameLuma: List<Float>,
        frameDelta: List<Float>,
    ): List<SceneEvent> {
        if (frameTimestampsUs.size < 4) return emptyList()
        val result = mutableListOf<SceneEvent>()
        for (i in 0 until frameTimestampsUs.size - 2) {
            for (j in i + 2 until frameTimestampsUs.size) {
                val lumaSim = abs(frameLuma[i] - frameLuma[j]) < 0.02f
                val deltaSim = abs(frameDelta[i] - frameDelta[j]) < 0.05f
                if (lumaSim && deltaSim) {
                    result.add(
                        SceneEvent(
                            timestampUs = frameTimestampsUs[j],
                            type = EventType.REPEATED_FRAME,
                            intensity = 0.8f,
                            detail = "Frame pattern repeated from ${TimeRange.formatUs(frameTimestampsUs[i])}",
                        )
                    )
                }
            }
        }
        return result
    }

    /** Audio discontinuities from segment RMS levels and jitter. */
    fun detectAudioDiscontinuities(segments: List<SegmentSignal>): List<SceneEvent> {
        if (segments.size < 3) return emptyList()
        val result = mutableListOf<SceneEvent>()
        for (i in 1 until segments.size) {
            val prev = segments[i - 1]
            val cur = segments[i]
            val levelDiff = abs(cur.audioLevel - prev.audioLevel)
            if (levelDiff > 0.5f || cur.audioJitter > 0.55f) {
                result.add(
                    SceneEvent(
                        timestampUs = cur.startUs,
                        type = EventType.AUDIO_DISCONTINUITY,
                        intensity = (levelDiff + cur.audioJitter) / 2f,
                        detail = if (levelDiff > 0.5f)
                            "Audio level jumps from ${(prev.audioLevel * 100).toInt()}% to ${(cur.audioLevel * 100).toInt()}"
                        else "High audio jitter ${(cur.audioJitter * 100).toInt()}%",
                    )
                )
            }
        }
        return result
    }

    /** Repeated temporal patterns: windows whose signal signature repeats. */
    fun detectPatterns(segments: List<SegmentSignal>): List<SceneEvent> {
        if (segments.size < 6) return emptyList()
        val result = mutableListOf<SceneEvent>()
        val window = 3
        for (i in 0 until segments.size - window * 2) {
            for (j in i + window until segments.size - window + 1) {
                var score = 0f
                for (k in 0 until window) {
                    val a = segments[i + k]
                    val b = segments[j + k]
                    score += 1f -
                        (abs(a.audioLevel - b.audioLevel) + abs(a.averageLuma - b.averageLuma)) / 2f
                }
                score /= window
                if (score > 0.85f) {
                    result.add(
                        SceneEvent(
                            timestampUs = segments[i].startUs,
                            type = EventType.PATTERN,
                            intensity = score,
                            detail = "Signal pattern repeats at ${TimeRange.formatUs(segments[j].startUs)}",
                        )
                    )
                    break
                }
            }
        }
        return result
    }

    companion object {
        fun buildSegmentSignals(
            durationsUs: List<Long>,
            lumaMeans: List<Float>,
            audioLevels: List<Float>,
        ): List<SegmentSignal> {
            val n = durationsUs.size
            require(n == lumaMeans.size && n == audioLevels.size) { "mismatched segment inputs" }
            val result = ArrayList<SegmentSignal>(n)
            var cursor = 0L
            for (i in 0 until n) {
                val start = cursor
                cursor += durationsUs[i]
                val prevLuma = if (i == 0) lumaMeans[i] else lumaMeans[i - 1]
                val prevAudio = if (i == 0) audioLevels[i] else audioLevels[i - 1]
                val frameDelta = abs(lumaMeans[i] - prevLuma)
                val jitter = abs(audioLevels[i] - prevAudio) * 0.3f + (0.1f * ((i % 17) / 17f))
                result.add(
                    SegmentSignal(
                        startUs = start,
                        endUs = cursor,
                        averageLuma = lumaMeans[i],
                        frameDelta = frameDelta,
                        audioLevel = audioLevels[i],
                        audioJitter = jitter,
                    )
                )
            }
            return result
        }
    }
}