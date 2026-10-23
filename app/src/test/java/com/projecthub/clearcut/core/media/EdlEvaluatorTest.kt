package com.projecthub.clearcut.core.media

import com.google.common.truth.Truth.assertThat
import com.projecthub.clearcut.core.model.AnalysisTrack
import com.projecthub.clearcut.core.model.Edl
import com.projecthub.clearcut.core.model.EdlOperation
import com.projecthub.clearcut.core.model.EditOperationType
import com.projecthub.clearcut.core.model.SyncPolicy
import com.projecthub.clearcut.core.model.TimeRange
import com.projecthub.clearcut.core.model.ValidationStatus
import org.junit.Test
import java.util.UUID

class EdlEvaluatorTest {

    private fun op(
        start: Long,
        end: Long,
        type: EditOperationType = EditOperationType.CUT,
        track: AnalysisTrack = AnalysisTrack.AUDIO,
        sync: SyncPolicy = SyncPolicy.CUT_AUDIO_WITH_VIDEO,
    ) = EdlOperation(
        id = UUID.randomUUID().toString(),
        sourceRange = TimeRange(start, end),
        track = track,
        operationType = type,
        syncPolicy = sync,
        validationStatus = ValidationStatus.VALID,
    )

    @Test
    fun `no cuts keeps full duration`() {
        val edl = Edl(mediaId = "m", sourceDurationUs = 60_000_000L)
        assertThat(EdlEvaluator.expectedDuration(edl)).isEqualTo(60_000_000L)
        assertThat(EdlEvaluator.computeSegments(edl)).containsExactly(
            EdlEvaluator.OutputSegment(TimeRange(0, 60_000_000), 0, 60_000_000)
        )
    }

    @Test
    fun `single cut shortens output`() {
        val edl = Edl(
            mediaId = "m",
            sourceDurationUs = 10_000_000L,
            operations = listOf(op(2_000_000, 4_000_000)),
        )
        assertThat(EdlEvaluator.expectedDuration(edl)).isEqualTo(8_000_000L)
        val segments = EdlEvaluator.computeSegments(edl)
        assertThat(segments).containsExactly(
            EdlEvaluator.OutputSegment(TimeRange(0, 2_000_000), 0, 2_000_000),
            EdlEvaluator.OutputSegment(TimeRange(4_000_000, 10_000_000), 2_000_000, 8_000_000),
        )
    }

    @Test
    fun `output to source mapping inverts correctly`() {
        val edl = Edl(
            mediaId = "m",
            sourceDurationUs = 10_000_000L,
            operations = listOf(op(2_000_000, 4_000_000)),
        )
        assertThat(EdlEvaluator.outputToSource(edl, 0L)).isEqualTo(0L)
        assertThat(EdlEvaluator.outputToSource(edl, 1_000_000L)).isEqualTo(1_000_000L)
        assertThat(EdlEvaluator.outputToSource(edl, 2_000_000L)).isEqualTo(4_000_000L)
        assertThat(EdlEvaluator.outputToSource(edl, 7_999_000L)).isEqualTo(9_999_000L)
    }

    @Test
    fun `overlapping cuts are detected`() {
        val edl = Edl(
            mediaId = "m",
            sourceDurationUs = 10_000_000L,
            operations = listOf(op(1_000_000, 4_000_000), op(3_000_000, 6_000_000)),
        )
        assertThat(EdlEvaluator.hasOverlappingCuts(edl)).isTrue()
    }

    @Test
    fun `non overlapping cuts are fine`() {
        val edl = Edl(
            mediaId = "m",
            sourceDurationUs = 10_000_000L,
            operations = listOf(op(1_000_000, 2_000_000), op(3_000_000, 4_000_000)),
        )
        assertThat(EdlEvaluator.hasOverlappingCuts(edl)).isFalse()
        assertThat(EdlEvaluator.validate(edl)).doesNotContain(ValidationStatus.INVALID)
    }

    @Test
    fun `invalid range is flagged`() {
        val bad = EdlOperation(
            id = "x",
            sourceRange = TimeRange(5_000_000, 2_000_000),
            track = AnalysisTrack.AUDIO,
            operationType = EditOperationType.CUT,
            validationStatus = ValidationStatus.UNVALIDATED,
        )
        val edl = Edl(mediaId = "m", sourceDurationUs = 10_000_000L, operations = listOf(bad))
        assertThat(EdlEvaluator.validate(edl)).containsExactly(ValidationStatus.INVALID)
    }

    @Test
    fun `replacement duration extends output`() {
        val replace = EdlOperation(
            id = "r",
            sourceRange = TimeRange(0, 1_000_000),
            track = AnalysisTrack.AUDIO,
            operationType = EditOperationType.REPLACE,
            replacementSourceUri = "file:///replacement.wav",
            replacementRange = TimeRange(0, 3_000_000),
            validationStatus = ValidationStatus.VALID,
        )
        val edl = Edl(mediaId = "m", sourceDurationUs = 5_000_000L, operations = listOf(replace))
        // CUT removes 1s; replacement adds 3s; remaining 4s kept => 7s.
        assertThat(EdlEvaluator.expectedDuration(edl)).isEqualTo(7_000_000L)
    }
}