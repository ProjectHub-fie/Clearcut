package com.projecthub.clearcut.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class EditOperationType { CUT, REPLACE, INSERT_VOICEOVER, INSERT_AMBIENCE, KEEP }

@Serializable
enum class SyncPolicy { KEEP_AUDIO, CUT_AUDIO_WITH_VIDEO, KEEP_VIDEO, CUT_VIDEO_WITH_AUDIO, NONE }

@Serializable
enum class ValidationStatus { UNVALIDATED, VALID, INVALID }

@Serializable
data class EdlOperation(
    val id: String,
    val sourceRange: TimeRange,
    val track: AnalysisTrack,
    val operationType: EditOperationType,
    val replacementSourceUri: String? = null,
    val replacementRange: TimeRange? = null,
    val targetRange: TimeRange = sourceRange,
    val syncPolicy: SyncPolicy = SyncPolicy.NONE,
    val crossfadeMs: Int = 120,
    val transition: TransitionType = TransitionType.CROSSFADE,
    val validationStatus: ValidationStatus = ValidationStatus.UNVALIDATED,
) {
    val isRemovalOrReplacement: Boolean
        get() = operationType != EditOperationType.KEEP
}

@Serializable
enum class TransitionType { NONE, CROSSFADE, CUT }

/**
 * Edit Decision List. The editor preview and the renderer consume the same
 * ordered list so the preview can never diverge from the exported result.
 */
@Serializable
data class Edl(
    val mediaId: String,
    val sourceDurationUs: Long,
    val operations: List<EdlOperation> = emptyList(),
    val expectedOutputDurationUs: Long = sourceDurationUs,
) {
    fun plus(operation: EdlOperation): Edl = copy(operations = operations + operation)

    /** Total removed source duration from cut-style operations. */
    val totalCutUs: Long
        get() = operations
            .filter { it.operationType == EditOperationType.CUT }
            .sumOf { it.sourceRange.durationUs }

    fun computeExpectedOutputDuration(): Long {
        var newTimeline = 0L
        // Simple sequential model: apply operations in order to the source timeline.
        val cuts = operations.filter { it.operationType == EditOperationType.CUT }
            .sortedBy { it.sourceRange.startUs }
        var cursor = 0L
        for (c in cuts) {
            newTimeline += (c.sourceRange.startUs - cursor).coerceAtLeast(0)
            cursor = c.sourceRange.endUs
        }
        newTimeline += (sourceDurationUs - cursor).coerceAtLeast(0)
        val replacements = operations.filter { it.operationType == EditOperationType.REPLACE }
        // A replacement op contributes its own replacement range duration to the timeline.
        // CUT ops already remove the source range; a REPLACE on the same range is expressed
        // as CUT + INSERT handled by renderer, so we keep duration = replacement duration.
        for (r in replacements) {
            val replDuration = r.sourceRange.durationUs
            newTimeline += r.replacementRange?.durationUs?.let { it - replDuration } ?: 0L
        }
        return newTimeline.coerceAtLeast(0L)
    }

    override fun toString(): String = "Edl(media=$mediaId, cuts=${operations.count { it.isRemovalOrReplacement }})"
}
