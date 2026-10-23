package com.projecthub.clearcut.core.scanner

import kotlin.math.PI
import kotlin.math.sin

/**
 * Deterministic synthetic media generation for tests. All content is generated
 * in-code (never shipped as a copyrighted fixture) with clearly-known repeated
 * audio and repeated video segments.
 */
object SyntheticMedia {

    /**
     * Generates PCM with a distinctive 440Hz reference tone in the first
     * "music block", silence, then the exact same block repeated at [repeatStartSample].
     * Returns (pcm, sampleRate).
     */
    fun audioWithRepeatedBlock(
        blockSamples: Int = 48_000 * 2,     // 2 seconds at 48k
        repeatAtOffsetSamples: Int = 48_000 * 6,
        sampleRate: Int = 48_000,
    ): FloatArray {
        val total = repeatAtOffsetSamples + blockSamples * 2
        val pcm = FloatArray(total)
        val tone = FloatArray(blockSamples) { i ->
            val phase = 2.0 * PI * 440.0 * i / sampleRate
            (sin(phase) * 0.4).toFloat()
        }
        // Block A at t=0..blockSamples
        add(pcm, tone, 0)
        // Leave silence.
        // Block A repeated at repeatAtOffsetSamples
        add(pcm, tone, repeatAtOffsetSamples)
        // Tail block to make the file end cleanly (same tone, so it reads as a repeated tail).
        add(pcm, tone, repeatAtOffsetSamples + blockSamples)
        return pcm
    }

    /** Generates PCM whose first and last halves are the same non-trivial waveform. */
    fun audioWithRepeatedHalves(totalSamples: Int = 48_000 * 6, sampleRate: Int = 48_000): FloatArray {
        val half = totalSamples / 2
        val pcm = FloatArray(totalSamples)
        for (i in 0 until half) {
            val phase = 2.0 * PI * 260.0 * i / sampleRate
            val v = (sin(phase) + 0.5 * sin(2.0 * phase) + 0.25 * sin(3.0 * phase)).toFloat()
            pcm[i] = v * 0.5f
            pcm[half + i] = v * 0.5f
        }
        return pcm
    }

    /** Generates a deterministic white-noise-ish waveform with NO repeated blocks. */
    fun audioWithoutRepeats(totalSamples: Int = 48_000 * 8, seed: Long = 42L): FloatArray {
        var s = seed
        return FloatArray(totalSamples) {
            s = s * 6364136223846793005L + 1442695040888963407L
            ((s ushr 33) and 0xFF) / 255f - 0.5f
        }
    }

    /** Builds an 8x8 grayscale frame (0..255). */
    fun frame(
        pattern: Int,
        width: Int = 64,
        height: Int = 64,
        intensity: Int = 160,
    ): ByteArray {
        val px = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = when (pattern % 3) {
                    0 -> (x + y + intensity) and 0xFF
                    1 -> (x * 3 + y * 7 + intensity) and 0xFF
                    else -> ((x xor y) + intensity) and 0xFF
                }
                px[y * width + x] = v.toByte()
            }
        }
        return px
    }

    /**
     * Frames at 30fps: scene A frames (n unique variations), a middle scene B,
     * then the same A-frame sequence again to form a repeated scene.
     */
    fun framesWithRepeatedScene(
        sceneALengthFrames: Int = 6,
        middleFrames: Int = 6,
        frameIntervalMs: Long = 33,
    ): List<Triple<ByteArray, Int, Int>> {
        val frames = mutableListOf<Triple<ByteArray, Int, Int>>()
        val sceneA = (0 until sceneALengthFrames).map { randomFrame(it + 1, 64, 64, 150L + it) }
        val sceneB = (0 until middleFrames).map { randomFrame(it + 30, 64, 64, 400L + it) }
        var ts = 0L
        fun push(seq: List<ByteArray>) {
            for (f in seq) {
                frames.add(Triple(f, 64, 64))
                ts += frameIntervalMs * 1000
            }
        }
        push(sceneA)
        push(sceneB)
        push(sceneA)
        return frames
    }

    /** Frames that all differ from each other, no repeats (deterministic seeds). */
    fun framesWithoutRepeats(count: Int = 12, width: Int = 64, height: Int = 64): List<Triple<ByteArray, Int, Int>> =
        (0 until count).map { pattern ->
            Triple(randomFrame(pattern, width, height, seedOffset = pattern * 7919L + 3), width, height)
        }

    /**
     * Deterministic pseudo-random grayscale frame. No two distinct seeds share
     * a structured pattern, which is essential for false-positive tests.
     */
    fun randomFrame(seed: Int, width: Int, height: Int, seedOffset: Long = 0L): ByteArray {
        var s = seed.toLong() * 6364136223846793005L + seedOffset
        val px = ByteArray(width * height)
        for (i in px.indices) {
            s = s * 6364136223846793005L + 1442695040888963407L
            px[i] = (((s ushr 33) and 0xFF) xor ((s ushr 40) and 0xFF)).toByte()
        }
        return px
    }

    private fun add(dst: FloatArray, src: FloatArray, offset: Int) {
        for (i in src.indices) {
            val j = offset + i
            if (j < dst.size) dst[j] += src[i]
        }
    }
}