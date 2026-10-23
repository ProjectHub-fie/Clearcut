package com.projecthub.clearcut.core.scanner

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VisualAnalyzerTest {

    private val visual = VisualAnalyzer(similarThreshold = 12, repeatedThreshold = 4)

    @Test
    fun `repeated scene is detected`() {
        val frames = SyntheticMedia.framesWithRepeatedScene()
        val hashes = frames.mapIndexed { i, (px, w, h) ->
            visual.hashFrame(px, w, h, i, i * 33_000L)
        }
        val repeated = visual.findRepeatedFrames(hashes)
        assertThat(repeated).isNotEmpty()
        // Repeated frames from the two copies of scene A.
        assertThat(repeated.maxOf { it.similarity }).isGreaterThan(0.5f)
    }

    @Test
    fun `serial scene changes are not repeated frames`() {
        val frames = SyntheticMedia.framesWithoutRepeats()
        val hashes = frames.mapIndexed { i, (px, w, h) ->
            visual.hashFrame(px, w, h, i, i * 50_000L)
        }
        assertThat(visual.findRepeatedFrames(hashes)).isEmpty()
    }

    @Test
    fun `hashing is deterministic`() {
        val f1 = SyntheticMedia.frame(1)
        val f2 = SyntheticMedia.frame(1)
        val h1 = visual.hashFrame(f1, 64, 64, 0, 0)
        val h2 = visual.hashFrame(f2, 64, 64, 1, 1)
        assertThat(h1.dHash).isEqualTo(h2.dHash)
        assertThat(h1.aHash).isEqualTo(h2.aHash)
        assertThat(h1.brightness).isEqualTo(h2.brightness)
    }

    @Test
    fun `different frames produce different hashes`() {
        val a = visual.hashFrame(SyntheticMedia.frame(1), 64, 64, 0, 0)
        val b = visual.hashFrame(SyntheticMedia.frame(30), 64, 64, 1, 1)
        assertThat(a.dHash).isNotEqualTo(b.dHash)
    }

    @Test
    fun `few frames produce no matches`() {
        val f = SyntheticMedia.frame(2)
        val hashes = listOf(visual.hashFrame(f, 64, 64, 0, 0))
        assertThat(visual.findRepeatedFrames(hashes)).isEmpty()
        assertThat(visual.findSimilarFrames(hashes)).isEmpty()
    }
}