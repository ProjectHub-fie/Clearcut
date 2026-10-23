package com.projecthub.clearcut.core.report

import com.google.common.truth.Truth.assertThat
import com.projecthub.clearcut.core.model.AnalysisCoverage
import com.projecthub.clearcut.core.model.AnalysisMethod
import com.projecthub.clearcut.core.model.AnalysisTrack
import com.projecthub.clearcut.core.model.CoverageSegment
import com.projecthub.clearcut.core.model.Edl
import com.projecthub.clearcut.core.model.EdlOperation
import com.projecthub.clearcut.core.model.EditOperationType
import com.projecthub.clearcut.core.model.Evidence
import com.projecthub.clearcut.core.model.Finding
import com.projecthub.clearcut.core.model.FindingCategory
import com.projecthub.clearcut.core.model.FindingStatus
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.MediaSource
import com.projecthub.clearcut.core.model.MediaType
import com.projecthub.clearcut.core.model.SamplingIntensity
import com.projecthub.clearcut.core.model.ScanReport
import com.projecthub.clearcut.core.model.ScanSettingsSnapshot
import com.projecthub.clearcut.core.model.TimeRange
import com.projecthub.clearcut.core.model.ValidationStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ReportGeneratorTest {

    private val media = MediaInfo(
        uri = "file:///tmp/a.mp4",
        displayName = "a.mp4",
        mimeType = "video/mp4",
        sizeBytes = 1234,
        durationUs = 60_000_000L,
        mediaType = MediaType.VIDEO,
        hasAudio = true,
        hasVideo = true,
        source = MediaSource.LOCAL,
    )

    private val report = ScanReport(
        mediaId = "m1",
        mediaName = "a.mp4",
        scannedAtEpochMs = 0L,
        mediaDurationUs = 60_000_000L,
        methodsUsed = listOf(AnalysisMethod.AUDIO_FINGERPRINT, AnalysisMethod.VISUAL_HASH),
        coverage = AnalysisCoverage(
            mediaDurationUs = 60_000_000L,
            analyzedSegments = listOf(CoverageSegment(TimeRange(0, 60_000_000), true)),
            unanalyzedRanges = emptyList(),
            droppedTracks = emptyList(),
            partialDecode = false,
        ),
        findings = listOf(
            Finding(
                id = "f1",
                category = FindingCategory.SIMILAR_AUDIO,
                status = FindingStatus.SUSPECTED,
                track = AnalysisTrack.AUDIO,
                method = AnalysisMethod.AUDIO_FINGERPRINT,
                range = TimeRange(1_000_000, 3_000_000),
                confidence = 0.87f,
                confidenceExplanation = "Fingerprint distance within threshold",
                evidence = listOf(Evidence("similarity", 0.87, null, "pairwise chunk match")),
                comparisonSource = null,
                note = "Technical observation only.",
            )
        ),
        proposedEdits = emptyList(),
        warnings = listOf("sample warning"),
        limitations = listOf("analysis uses local reference set only"),
        settings = ScanSettingsSnapshot(
            windowSeconds = 60,
            sampling = SamplingIntensity.MEDIUM,
            referenceFiles = emptyList(),
            lowResourceMode = false,
            compareAudio = true,
            compareVisual = true,
            minConfidence = 0.6f,
        ),
    )

    @Test
    fun `json report is valid and contains disclaimer`() {
        val json = ReportGenerator.toJson(media, report, null)
        assertThat(json).contains("clearcut/report/v1")
        assertThat(json).contains(ReportGenerator.DISCLAIMER.substring(0, 60))
        // Actually parseable as JSON.
        val parsed = Json.parseToJsonElement(json)
        assertThat(parsed.jsonObject.keys).contains("findings")
    }

    @Test
    fun `txt report lists findings and limitations`() {
        val txt = ReportGenerator.toTxt(media, report, null)
        assertThat(txt).contains("a.mp4")
        assertThat(txt).contains("Similar audio")
        assertThat(txt).doesNotContain("No similarity findings above the configured threshold in the analyzed reference set.")
        assertThat(txt).contains("uses local reference set only")
        assertThat(txt).contains(ReportGenerator.DISCLAIMER)
    }

    @Test
    fun `empty findings still produces report with no-findings state`() {
        val r = report.copy(findings = emptyList())
        val txt = ReportGenerator.toTxt(media, r, null)
        assertThat(txt).contains("No similarity findings above the configured threshold in the analyzed reference set.")
    }

    @Test
    fun `edl operations are embedded in report`() {
        val edl = Edl(
            mediaId = "m1",
            sourceDurationUs = 60_000_000L,
            operations = listOf(
                EdlOperation(
                    id = "op1",
                    sourceRange = TimeRange(1_000_000, 2_000_000),
                    track = AnalysisTrack.AUDIO,
                    operationType = EditOperationType.CUT,
                    syncPolicy = com.projecthub.clearcut.core.model.SyncPolicy.CUT_AUDIO_WITH_VIDEO,
                    validationStatus = ValidationStatus.VALID,
                )
            ),
        )
        val txt = ReportGenerator.toTxt(media, report, edl)
        assertThat(txt).contains("CUT")
        assertThat(txt).contains("replacement=none")
        val json = ReportGenerator.toJson(media, report, edl)
        assertThat(json).contains("CUT")
        assertThat(json).contains("op1")
        assertThat(json).contains("approvedEdits")
    }
}