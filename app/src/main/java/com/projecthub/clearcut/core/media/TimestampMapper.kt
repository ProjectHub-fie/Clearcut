package com.projecthub.clearcut.core.media

import com.projecthub.clearcut.core.model.TimeRange

/**
 * Pure timestamp mapping utilities.
 *
 * The scanner works in decoded microseconds throughout. Source containers may
 * deliver timestamps with offsets or non-zero start (e.g. after a seek table);
 * this mapper keeps the mapping explicit and testable without Android.
 */
object TimestampMapper {

    /**
     * Normalize a list of raw decoded timestamps into a continuous 0-based timeline.
     * Handles non-zero start, out-of-order samples and gaps larger than expected.
     */
    fun normalizeTimestamps(rawTimestampsUs: List<Long>): List<Long> {
        if (rawTimestampsUs.isEmpty()) return emptyList()
        val sorted = rawTimestampsUs.sorted().distinct()
        val start = sorted.first()
        return sorted.map { (it - start).coerceAtLeast(0L) }
    }

    /** Convert a decoded timestamp to a source timestamp given the base offset. */
    fun toSourceUs(decodedUs: Long, baseOffsetUs: Long): Long = decodedUs + baseOffsetUs

    /** Convert a source timestamp to an output timeline timestamp given a start offset. */
    fun toOutputUs(sourceUs: Long, startOffsetUs: Long = 0L): Long = (sourceUs - startOffsetUs).coerceAtLeast(0L)

    /**
     * Merge overlapping or adjacent candidate intervals.
     * Intervals closer than [gapToleranceUs] and sharing [sameTrack] are merged
     * to avoid fragmenting one repeated passage into many findings.
     */
    fun mergeIntervals(
        intervals: List<TimeRange>,
        gapToleranceUs: Long = 2_000_000L,
    ): List<TimeRange> {
        if (intervals.size <= 1) return intervals
        val sorted = intervals.sortedBy { it.startUs }
        val result = mutableListOf<TimeRange>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            if (next.startUs - current.endUs <= gapToleranceUs) {
                current = TimeRange(current.startUs, maxOf(current.endUs, next.endUs))
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }

    /**
     * Compute coverage segments over [durationUs] from the list of analyzed windows.
     * Any gap larger than [minGapUs] is reported as unanalyzed.
     */
    fun computeCoverage(
        durationUs: Long,
        analyzedWindowsUs: List<TimeRange>,
        partialDecode: Boolean,
    ): Pair<List<TimeRange>, List<TimeRange>> {
        if (durationUs <= 0L) return emptyList<TimeRange>() to emptyList()
        val merged = mergeIntervals(analyzedWindowsUs, gapToleranceUs = 500_000L)
            .filter { it.endUs > it.startUs }
            .map { TimeRange(it.startUs.coerceAtLeast(0L), it.endUs.coerceAtMost(durationUs)) }
            .filter { it.durationUs > 0 }
        val unanalyzed = mutableListOf<TimeRange>()
        var cursor = 0L
        for (seg in merged) {
            if (seg.startUs > cursor) unanalyzed.add(TimeRange(cursor, seg.startUs))
            cursor = seg.endUs
        }
        if (cursor < durationUs) unanalyzed.add(TimeRange(cursor, durationUs))
        return merged to unanalyzed
    }

    /**
     * Map a source timestamp to an output timeline after cutting a set of
     * non-overlapping, sorted edit ranges with crossfade handling.
     * Returns output timestamp for the given source position (-1 if inside a cut).
     */
    fun mapSourceToOutput(
        sourceUs: Long,
        sourceDurationUs: Long,
        cutsSortedUs: List<TimeRange>,
    ): Long {
        var outputUs = sourceUs
        var shifted = 0L
        for (cut in cutsSortedUs) {
            if (sourceUs >= cut.endUs) {
                shifted += cut.durationUs
            } else if (sourceUs in cut.startUs until cut.endUs) {
                return -1L
            } else {
                break
            }
        }
        outputUs -= shifted
        return outputUs.coerceIn(0L, sourceDurationUs)
    }
}
