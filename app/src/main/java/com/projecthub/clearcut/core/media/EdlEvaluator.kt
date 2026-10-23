package com.projecthub.clearcut.core.media

import com.projecthub.clearcut.core.model.Edl
import com.projecthub.clearcut.core.model.EdlOperation
import com.projecthub.clearcut.core.model.EditOperationType
import com.projecthub.clearcut.core.model.TimeRange
import com.projecthub.clearcut.core.model.ValidationStatus

/**
 * Computes the output timeline of an [Edl] and validates the whole decision list
 * before rendering. Both the preview player and the exporter consume the exact
 * same mapping, so what you preview is what gets exported.
 */
object EdlEvaluator {

    /** Output paragraphs: contiguous source intervals surviving the EDL. */
    data class OutputSegment(
        val sourceRange: TimeRange,
        val outputStartUs: Long,
        val outputEndUs: Long,
    )

    fun computeSegments(edl: Edl): List<OutputSegment> {
        val cuts = edl.operations
            .filter { it.operationType == EditOperationType.CUT }
            .sortedBy { it.sourceRange.startUs }
        val result = mutableListOf<OutputSegment>()
        var cursor = 0L
        var outputCursor = 0L
        for (c in cuts) {
            if (c.sourceRange.startUs > cursor) {
                val segStart = cursor
                val segEnd = minOf(c.sourceRange.startUs, edl.sourceDurationUs)
                result.add(
                    OutputSegment(
                        sourceRange = TimeRange(segStart, segEnd),
                        outputStartUs = outputCursor,
                        outputEndUs = outputCursor + (segEnd - segStart),
                    )
                )
                outputCursor += (segEnd - segStart)
            }
            cursor = maxOf(cursor, c.sourceRange.endUs)
        }
        if (cursor < edl.sourceDurationUs) {
            result.add(
                OutputSegment(
                    sourceRange = TimeRange(cursor, edl.sourceDurationUs),
                    outputStartUs = outputCursor,
                    outputEndUs = outputCursor + (edl.sourceDurationUs - cursor),
                )
            )
        }
        return result
    }

    /** Map an output timeline position to the corresponding source position. */
    fun outputToSource(edl: Edl, outputUs: Long): Long {
        for (seg in computeSegments(edl)) {
            if (outputUs in seg.outputStartUs until seg.outputEndUs) {
                return seg.sourceRange.startUs + (outputUs - seg.outputStartUs)
            }
        }
        return edl.sourceDurationUs
    }

    fun validate(edl: Edl): List<ValidationStatus> {
        val opStatus = edl.operations.map { op ->
            when {
                op.sourceRange.startUs < 0 || op.sourceRange.endUs > edl.sourceDurationUs ||
                    op.sourceRange.endUs <= op.sourceRange.startUs -> ValidationStatus.INVALID
                else -> ValidationStatus.VALID
            }
        }
        return opStatus
    }

    /** Deterministic expected output duration from the EDL. */
    fun expectedDuration(edl: Edl): Long = edl.computeExpectedOutputDuration()

    /** Detect overlap among cut ranges (invalid EDL). */
    fun hasOverlappingCuts(edl: Edl): Boolean {
        val cuts = edl.operations.filter { it.operationType == EditOperationType.CUT }.sortedBy { it.sourceRange.startUs }
        for (i in 0 until cuts.size - 1) {
            if (cuts[i].sourceRange.endUs > cuts[i + 1].sourceRange.startUs) return true
        }
        return false
    }

    /** Builds a Media3-like timeline from EDL. Callers adapt this to ExoPlayer/Transformer. */
    fun timelineClips(edl: Edl): List<EdlOperation> = edl.operations.filter { it.operationType == EditOperationType.KEEP }
}