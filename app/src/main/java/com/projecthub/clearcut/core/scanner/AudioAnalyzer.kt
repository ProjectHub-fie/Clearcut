package com.projecthub.clearcut.core.scanner

import com.projecthub.clearcut.core.model.TimeRange
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Database-free audio self-similarity analysis.
 *
 * Decoded PCM is reduced to short fingerprints (RMS energy, zero-crossing rate,
 * spectral-centroid proxy, low-order autocorrelation ratios). Chunks are compared
 * pairwise within the file (and optionally against other user-selected files) to
 * discover repeated or highly similar passages. This is a technical observation
 * and never identifies a song or copyright owner.
 *
 * Pure Kotlin: no Android classes, so it runs in JVM unit tests on synthetic PCM.
 */
class AudioAnalyzer(
    private val sampleRate: Int,
    private val similarThreshold: Float = 0.92f,
    private val repeatedThreshold: Float = 0.97f,
    private val minPassageChunks: Int = 4,
) {

    data class ChunkFingerprint(
        val startSample: Long,
        val endSample: Long,
        val rms: Float,
        val zeroCrossings: Float,
        val centroid: Float,
        val autocorr: FloatArray,
    )

    data class Match(
        val firstRange: TimeRange,
        val secondRange: TimeRange,
        val similarity: Float,
    )

    fun fingerprintChunks(
        pcm: FloatArray,
        chunkSize: Int = 4096,
        hopSize: Int = 2048,
    ): List<ChunkFingerprint> {
        if (pcm.isEmpty()) return emptyList()
        val result = ArrayList<ChunkFingerprint>(pcm.size / hopSize + 1)
        var i = 0
        while (i + chunkSize <= pcm.size) {
            val window = FloatArray(chunkSize)
            System.arraycopy(pcm, i, window, 0, chunkSize)
            result.add(computeFingerprint(window, i.toLong(), (i + chunkSize).toLong()))
            i += hopSize
        }
        return result
    }

    private fun computeFingerprint(window: FloatArray, startSample: Long, endSample: Long): ChunkFingerprint {
        var sumSq = 0.0
        var crossings = 0
        var prev = 0f
        for (j in window.indices) {
            val s = window[j]
            sumSq += (s * s).toDouble()
            if (prev != 0f && (s < 0f) != (prev < 0f)) crossings++
            prev = s
        }
        val rms = sqrt(sumSq / window.size).toFloat()
        val zcr = crossings.toFloat() / window.size

        // Spectral centroid proxy: imbalance of |amplitude| between halves; an
        // amplitude/phase independent texture descriptor.
        val half = window.size / 2
        var first = 0f
        var second = 0f
        for (j in 0 until half) {
            first += abs(window[j])
            second += abs(window[j + half])
        }
        val centroid = ((second - first) / (first + second + 1e-6f)).coerceIn(-1f, 1f)

        val lag1 = autocorrRatio(window, 1)
        val lag2 = autocorrRatio(window, 4)
        val lag3 = autocorrRatio(window, 16)
        return ChunkFingerprint(
            startSample = startSample,
            endSample = endSample,
            rms = rms,
            zeroCrossings = zcr,
            centroid = centroid,
            autocorr = floatArrayOf(lag1, lag2, lag3),
        )
    }

    private fun autocorrRatio(window: FloatArray, lag: Int): Float {
        var numerator = 0.0
        var denominator = 0.0
        for (i in 0 until window.size - lag) {
            numerator += window[i].toDouble() * window[i + lag]
            denominator += window[i].toDouble() * window[i]
        }
        return if (denominator > 1e-10) (numerator / denominator).toFloat().coerceIn(-1f, 1f) else 0f
    }

    /**
     * Pairwise self-similarity search. Returns repeated passages (>= repeatedThreshold)
     * and similar passages (>= similarThreshold), grouped into contiguous passages.
     */
    fun findSelfSimilarities(fps: List<ChunkFingerprint>): List<Match> {
        if (fps.size < 40) return emptyList()
        val n = fps.size
        val vectors = fps.map { featureVector(it) }
        val mean = FloatArray(FEATURE_DIM) { k -> vectors.sumOf { it[k].toDouble() }.toFloat() / n }
        val matrix = FloatArray(n * n) { 0f }
        for (i in 0 until n) {
            for (j in i + 21 until n) {
                val sim = similarity(vectors[i], vectors[j], mean, mean)
                if (sim >= similarThreshold) matrix[i * n + j] = sim
            }
        }
        return chainPassages(matrix, n)
    }

    /** Cross-file similarity against another fingerprint set. */
    fun findCrossFileSimilarities(
        self: List<ChunkFingerprint>,
        other: List<ChunkFingerprint>,
    ): List<Match> {
        if (self.isEmpty() || other.isEmpty()) return emptyList()
        val nA = self.size
        val nB = other.size
        val aVectors = self.map { featureVector(it) }
        val bVectors = other.map { featureVector(it) }
        val meanA = FloatArray(FEATURE_DIM) { k -> aVectors.sumOf { it[k].toDouble() }.toFloat() / nA }
        val meanB = FloatArray(FEATURE_DIM) { k -> bVectors.sumOf { it[k].toDouble() }.toFloat() / nB }
        val matrix = FloatArray(nA * nB) { 0f }
        for (i in 0 until nA) {
            for (j in 0 until nB) {
                val sim = similarity(aVectors[i], bVectors[j], meanA, meanB)
                if (sim >= similarThreshold) matrix[i * nB + j] = sim
            }
        }
        return chainCrossPassages(matrix, nA, nB)
    }

    /**
     * Centered cosine similarity. Features are normalized against the corpus mean
     * first, so chunks that merely share the file's average texture (for example
     * flat noise) do not produce false "repeated" matches: their centered vector
     * is near zero and the similarity collapses to 0. Truly repeated motifs,
     * which deviate from the mean in a consistent way, still match at ~1.
     */
    private fun similarity(a: FloatArray, b: FloatArray, meanA: FloatArray, meanB: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (k in 0 until FEATURE_DIM) {
            val av = a[k] - meanA[k]
            val bv = b[k] - meanB[k]
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        if (normA <= 1e-6f || normB <= 1e-6f) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).coerceIn(-1f, 1f) * 0.5f + 0.5f
    }

    private fun featureVector(f: ChunkFingerprint): FloatArray = floatArrayOf(
        normalizedRms(f.rms) * 2f,
        f.zeroCrossings,
        f.centroid * 0.5f + 0.5f,
        f.autocorr[0] * 0.5f + 0.5f,
        f.autocorr[1] * 0.5f + 0.5f,
        f.autocorr[2] * 0.5f + 0.5f,
    )

    companion object {
        private const val FEATURE_DIM = 6
    }

    private fun normalizedRms(rms: Float): Float =
        (10f * ln(rms * 100f + 1e-4f)).coerceIn(0f, 1f)

    /** Convert the raw similarity matrix into contiguous passages via diagonal runs. */
    private fun chainPassages(matrix: FloatArray, n: Int): List<Match> {
        val runs = mutableListOf<Match>()
        for (i in 0 until n - 2) {
            for (j in i + 21 until n - 1) {
                val s = matrix[i * n + j]
                if (s <= 0f) continue
                var len = 1
                var best = s
                while (i + len < n && j + len < n && matrix[(i + len) * n + (j + len)] >= similarThreshold) {
                    best = maxOf(best, matrix[(i + len) * n + (j + len)])
                    len++
                }
                // A single 42ms cell can correlate by chance on noise; require a
                // contiguous passage of several chunks before reporting a repeat.
                if (len >= minPassageChunks) {
                    runs.add(
                        Match(
                            firstRange = TimeRange(chunkStartUs(i), chunkStartUs(i + len)),
                            secondRange = TimeRange(chunkStartUs(j), chunkStartUs(j + len)),
                            similarity = best,
                        )
                    )
                }
            }
        }
        return mergeCloseRuns(runs).distinctBy { "${it.firstRange.startUs}-${it.secondRange.startUs}" }
    }

    private fun chainCrossPassages(matrix: FloatArray, nA: Int, nB: Int): List<Match> {
        val runs = mutableListOf<Match>()
        for (i in 0 until nA - 1) {
            for (j in 0 until nB - 1) {
                val s = matrix[i * nB + j]
                if (s <= 0f) continue
                var len = 1
                var best = s
                while (i + len < nA && j + len < nB && matrix[(i + len) * nB + (j + len)] >= similarThreshold) {
                    best = maxOf(best, matrix[(i + len) * nB + (j + len)])
                    len++
                }
                // Cross-file passages: a single piece can correlate by chance too.
                if (len >= minPassageChunks) {
                    runs.add(
                        Match(
                            firstRange = TimeRange(chunkStartUs(i), chunkStartUs(i + len)),
                            secondRange = TimeRange(chunkStartUs(j), chunkStartUs(j + len)),
                            similarity = best,
                        )
                    )
                }
            }
        }
        return mergeCloseRuns(runs).distinctBy { "${it.firstRange.startUs}-${it.secondRange.startUs}" }
    }

    private fun mergeCloseRuns(runs: List<Match>): List<Match> {
        if (runs.size <= 1) return runs
        val sorted = runs.sortedBy { it.firstRange.startUs }
        val result = mutableListOf<Match>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            val gapA = next.firstRange.startUs - current.firstRange.endUs
            val gapB = next.secondRange.startUs - current.secondRange.endUs
            if (gapA in 0..500_000 && gapB in 0..500_000) {
                current = Match(
                    firstRange = TimeRange(
                        current.firstRange.startUs,
                        maxOf(current.firstRange.endUs, next.firstRange.endUs),
                    ),
                    secondRange = TimeRange(
                        current.secondRange.startUs,
                        maxOf(current.secondRange.endUs, next.secondRange.endUs),
                    ),
                    similarity = maxOf(current.similarity, next.similarity),
                )
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }

    private fun chunkStartUs(chunkIndex: Int): Long = chunkIndex * 2048L * 1_000_000L / sampleRate

    /** Similarity of a chunk against its neighbors, for the interval-based API. */
    fun analyzeIntervals(
        pcm: FloatArray,
        chunkSize: Int = 4096,
        hopSize: Int = 2048,
    ): List<Match> = findSelfSimilarities(fingerprintChunks(pcm, chunkSize, hopSize))
}