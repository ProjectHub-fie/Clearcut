package com.projecthub.clearcut.core.scanner

import com.projecthub.clearcut.core.model.TimeRange
import kotlin.math.abs

/**
 * Database-free visual similarity analysis using perceptual hashes.
 *
 * Each sampled frame is downscaled to a small grayscale bitmap and turned into a
 * 64-bit difference-hash (dHash) plus a mean-hash (aHash). Repeated or similar
 * frames/scenes are detected through Hamming distances.
 *
 * Pure Kotlin: the caller supplies small downscaled frame pixel arrays, so this
 * class is fully testable on the JVM.
 */
class VisualAnalyzer(
    private val similarThreshold: Int = 12,   // max Hamming bits considered "similar"
    private val repeatedThreshold: Int = 4,   // max Hamming bits considered "repeated"
) {

    data class FrameHash(
        val frameIndex: Int,
        val timestampUs: Long,
        val dHash: Long,
        val aHash: Long,
        val brightness: Float,
        val edgeEnergy: Float,
    )

    data class Match(
        val firstRange: TimeRange,
        val secondRange: TimeRange,
        val similarity: Float,
        val hamming: Int,
    )

    /** Compute aHash+dHash from a width x height grayscale (0..255) frame. */
    fun hashFrame(pixels: ByteArray, width: Int, height: Int, frameIndex: Int, timestampUs: Long): FrameHash {
        require(pixels.size >= width * height) { "pixel buffer too small" }
        // Downscale to 9x8 (dHash needs 9x8; aHash uses 8x8 block averages).
        val dBlockW = width / 9
        val dBlockH = height / 8
        val grid = Array(9) { x ->
            DoubleArray(8) { y ->
                var acc = 0.0
                var x0 = x * dBlockW
                var x1 = (x + 1) * dBlockW
                var y0 = y * dBlockH
                var y1 = (y + 1) * dBlockH
                if (x0 >= width) x0 = width - 1
                if (x1 > width) x1 = width
                if (y0 >= height) y0 = height - 1
                if (y1 > height) y1 = height
                var count = 0
                for (py in y0 until y1) {
                    var po = py * width + x0
                    for (px in x0 until x1) {
                        acc += pixels[po].toInt() and 0xFF
                        count++
                        po++
                    }
                }
                acc / count.coerceAtLeast(1)
            }
        }
        var dHash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (grid[x][y] > grid[x + 1][y]) dHash = dHash or (1L shl (y * 8 + x))
            }
        }
        var aHash = 0L
        val allValues = grid.flatMap { it.toList() }
        val avg = allValues.average()
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (grid[x][y] > avg) aHash = aHash or (1L shl bit)
                bit++
            }
        }
        val brightness = allValues.average().toFloat() / 255f
        var edgeEnergy = 0f
        for (y in 0 until 7) {
            for (x in 0 until 8) {
                val diff = abs(grid[x][y + 1] - grid[x][y]).toFloat()
                if (diff > 24f) edgeEnergy += 1f
            }
        }
        edgeEnergy /= 56f
        return FrameHash(frameIndex, timestampUs, dHash, aHash, brightness, edgeEnergy)
    }

    private fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Detect self-similar frames: same or similar content at different timestamps. */
    fun findRepeatedFrames(frames: List<FrameHash>): List<Match> {
        if (frames.size < 4) return emptyList()
        val matches = mutableListOf<Match>()
        for (i in frames.indices) {
            for (j in i + 1 until frames.size) {
                val dh = hamming(frames[i].dHash, frames[j].dHash)
                if (dh <= repeatedThreshold) {
                    val sim = confidence(dh)
                    matches.add(
                        Match(
                            firstRange = TimeRange(frames[i].timestampUs, frames[i].timestampUs + 500_000),
                            secondRange = TimeRange(frames[j].timestampUs, frames[j].timestampUs + 500_000),
                            similarity = sim,
                            hamming = dh,
                        )
                    )
                }
            }
        }
        return mergeCloseRuns(matches)
    }

    /** Similar (not necessarily repeated) frames. */
    fun findSimilarFrames(frames: List<FrameHash>): List<Match> {
        if (frames.size < 4) return emptyList()
        val matches = mutableListOf<Match>()
        for (i in frames.indices) {
            for (j in i + 1 until frames.size) {
                val dh = hamming(frames[i].dHash, frames[j].dHash)
                if (dh > repeatedThreshold && dh <= similarThreshold) {
                    matches.add(
                        Match(
                            firstRange = TimeRange(frames[i].timestampUs, frames[i].timestampUs + 500_000),
                            secondRange = TimeRange(frames[j].timestampUs, frames[j].timestampUs + 500_000),
                            similarity = confidence(dh),
                            hamming = dh,
                        )
                    )
                }
            }
        }
        return mergeCloseRuns(matches)
    }

    /** Repeated vs similar grouped by a caller-chosen classification. */
    fun repeatedVsSimilar(frames: List<FrameHash>): Pair<List<Match>, List<Match>> =
        findRepeatedFrames(frames) to findSimilarFrames(frames)

    private fun confidence(hammingBits: Int): Float {
        val maxBits = 64
        val ratio = 1f - hammingBits.toFloat() / maxBits
        // Polynomial-ish mapping: bits 0..3 -> >0.9, bits ..12 -> 0.6..0.9
        return (ratio * ratio).coerceIn(0f, 1f)
    }

    private fun mergeCloseRuns(matches: List<Match>): List<Match> {
        if (matches.size <= 1) return matches
        val sorted = matches.sortedBy { it.firstRange.startUs }
        val result = mutableListOf<Match>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            val gapA = next.firstRange.startUs - current.firstRange.endUs
            val gapB = next.secondRange.startUs - current.secondRange.endUs
            if (gapA in 0..2_000_000 && gapB in 0..2_000_000) {
                current = Match(
                    firstRange = TimeRange(current.firstRange.startUs, maxOf(current.firstRange.endUs, next.firstRange.endUs)),
                    secondRange = TimeRange(current.secondRange.startUs, maxOf(current.secondRange.endUs, next.secondRange.endUs)),
                    similarity = maxOf(current.similarity, next.similarity),
                    hamming = minOf(current.hamming, next.hamming),
                )
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }
}