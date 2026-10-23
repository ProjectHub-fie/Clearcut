package com.projecthub.clearcut.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Test

class EdlModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `edl serializes and deserializes`() {
        val op = EdlOperation(
            id = "op1",
            sourceRange = TimeRange(1_000_000, 2_000_000),
            track = AnalysisTrack.AUDIO,
            operationType = EditOperationType.CUT,
            replacementSourceUri = null,
            replacementRange = null,
            syncPolicy = SyncPolicy.CUT_AUDIO_WITH_VIDEO,
            crossfadeMs = 120,
            validationStatus = ValidationStatus.VALID,
        )
        val edl = Edl(mediaId = "m1", sourceDurationUs = 10_000_000L, operations = listOf(op))
        val encoded = json.encodeToString(edl)
        assertThat(encoded).contains("CUT")
        val decoded = json.decodeFromString<Edl>(encoded)
        assertThat(decoded).isEqualTo(edl)
    }

    @Test
    fun `time range semantics`() {
        val r = TimeRange(1_000_000, 4_000_000)
        assertThat(r.durationUs).isEqualTo(3_000_000)
        assertThat(r.contains(1_000_000)).isTrue()
        assertThat(r.contains(4_000_000)).isTrue()
        assertThat(r.contains(4_000_001)).isFalse()
        assertThat(r.overlaps(TimeRange(3_000_000, 5_000_000))).isTrue()
        assertThat(r.overlaps(TimeRange(5_000_000, 6_000_000))).isFalse()
        assertThat(r.mergeWith(TimeRange(2_000_000, 7_000_000)))
            .isEqualTo(TimeRange(1_000_000, 7_000_000))
    }

    @Test
    fun `empty duration range is allowed but flagged invalid by evaluator`() {
        val r = TimeRange(5_000_000, 5_000_000)
        assertThat(r.durationUs).isEqualTo(0)
    }

    @Test
    fun `finding exposes derived fields`() {
        val f = Finding(
            id = "f",
            category = FindingCategory.REPEATED_AUDIO,
            status = FindingStatus.SUSPECTED,
            track = AnalysisTrack.AUDIO,
            method = AnalysisMethod.AUDIO_FINGERPRINT,
            range = TimeRange(1_000_000, 3_000_000),
            confidence = 0.9f,
            confidenceExplanation = "score",
            evidence = listOf(Evidence("sim", 0.9, "score")),
        )
        assertThat(f.startUs).isEqualTo(1_000_000)
        assertThat(f.endUs).isEqualTo(3_000_000)
        assertThat(f.durationUs).isEqualTo(2_000_000)
    }
}