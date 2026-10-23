package com.projecthub.clearcut.core.scanner

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StructuralAnalyzerTest {

    private val structural = StructuralAnalyzer()

    @Test
    fun `scene boundary detected on large luma jump`() {
        // Long, mostly steady sequence with two isolated large jumps: the jumps
        // must stand out statistically as scene boundaries.
        val ts = (0 until 60).map { it * 100_000L }
        val luma = (0 until 60).map { i -> if (i == 20 || i == 45) 0.9f else 0.1f + (i % 5) * 0.01f }
        val events = structural.detectSceneChanges(ts, luma)
        assertThat(events).isNotEmpty()
        val boundaries = events.filter { it.type == StructuralAnalyzer.EventType.SCENE_BOUNDARY }
        assertThat(boundaries).isNotEmpty()
        assertThat(boundaries.any { it.timestampUs == 2_000_000L || it.timestampUs == 4_500_000L }).isTrue()
    }

    @Test
    fun `smooth video produces no scene boundary`() {
        val ts = (0 until 40).map { it * 100_000L }
        val luma = (0 until 40).map { 0.2f + it * 0.002f }
        assertThat(structural.detectSceneChanges(ts, luma)).isEmpty()
    }

    @Test
    fun `repeated frames detected by matching signature`() {
        val ts = (0 until 6).map { it * 100_000L }
        val luma = listOf(0.5f, 0.51f, 0.5f, 0.5f, 0.51f, 0.5f)
        val delta = listOf(0.01f, 0.01f, 0.01f, 0.01f, 0.01f, 0.01f)
        val repeated = structural.detectRepeatedFrames(ts, luma, delta)
        assertThat(repeated).isNotEmpty()
    }

    @Test
    fun `silence then loud block raises audio discontinuity`() {
        val segments = StructuralAnalyzer.buildSegmentSignals(
            durationsUs = listOf(1_000_000L, 1_000_000L, 1_000_000L),
            lumaMeans = listOf(0f, 0f, 0f),
            audioLevels = listOf(0.05f, 0.05f, 0.95f),
        )
        val events = structural.detectAudioDiscontinuities(segments)
        assertThat(events).isNotEmpty()
        assertThat(events.first().type).isEqualTo(StructuralAnalyzer.EventType.AUDIO_DISCONTINUITY)
    }

    @Test
    fun `mismatched inputs throw`() {
        var threw = false
        try {
            StructuralAnalyzer.buildSegmentSignals(
                durationsUs = listOf(1_000_000L),
                lumaMeans = listOf(0f, 1f),
                audioLevels = listOf(0f),
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }
}