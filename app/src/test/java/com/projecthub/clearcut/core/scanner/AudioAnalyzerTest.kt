package com.projecthub.clearcut.core.scanner

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioAnalyzerTest {

    private val sampleRate = 48_000
    private val analyzer = AudioAnalyzer(sampleRate = sampleRate, similarThreshold = 0.92f, repeatedThreshold = 0.97f)

    @Test
    fun `repeated audio block is detected as repeated`() {
        val pcm = SyntheticMedia.audioWithRepeatedBlock()
        val fps = analyzer.fingerprintChunks(pcm, chunkSize = 4096, hopSize = 2048)
        assertThat(fps).isNotEmpty()
        val matches = analyzer.findSelfSimilarities(fps)
        assertThat(matches).isNotEmpty()

        // At least one matched passage should be strong to count as repeated.
        val best = matches.maxByOrNull { it.similarity }!!
        assertThat(best.similarity).isGreaterThan(0.6f)
        // first and second ranges should be separated (non-overlapping copies).
        assertThat(best.secondRange.startUs).isGreaterThan(best.firstRange.startUs)
    }

    @Test
    fun `audio without repeats yields few or no matches`() {
        // 8 seconds of deterministic white noise: no self-repeats by construction.
        val pcm = SyntheticMedia.audioWithoutRepeats(totalSamples = 48_000 * 8)
        val fps = analyzer.fingerprintChunks(pcm, chunkSize = 4096, hopSize = 2048)
        val matches = analyzer.findSelfSimilarities(fps)
        assertThat(matches).hasSize(0)
    }

    @Test
    fun `identical halves report repeated pattern`() {
        val pcm = SyntheticMedia.audioWithRepeatedHalves(totalSamples = 48_000 * 8)
        val fps = analyzer.fingerprintChunks(pcm, chunkSize = 4096, hopSize = 2048)
        val matches = analyzer.findSelfSimilarities(fps)
        assertThat(matches).isNotEmpty()
        val best = matches.maxByOrNull { it.similarity }!!
        assertThat(best.similarity).isGreaterThan(0.7f)
    }

    @Test
    fun `cross file similarity is surfaced`() {
        val pcmA = SyntheticMedia.audioWithRepeatedHalves(totalSamples = 48_000 * 4)
        val pcmB = SyntheticMedia.audioWithRepeatedHalves(totalSamples = 48_000 * 4)
        val fa = analyzer.fingerprintChunks(pcmA)
        val fb = analyzer.fingerprintChunks(pcmB)
        val matches = analyzer.findCrossFileSimilarities(fa, fb)
        assertThat(matches).isNotEmpty()
    }

    @Test
    fun `empty pcm produces empty fingerprints`() {
        assertThat(analyzer.fingerprintChunks(FloatArray(0))).isEmpty()
        assertThat(analyzer.findSelfSimilarities(emptyList())).isEmpty()
    }

    @Test
    fun `too few chunks produce no matches`() {
        val short = FloatArray(4096 * 10) { (it % 7) * 0.1f }
        val fps = analyzer.fingerprintChunks(short)
        assertThat(analyzer.findSelfSimilarities(fps)).isEmpty()
    }
}