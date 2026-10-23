package com.projecthub.clearcut.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AnalysisMethod { AUDIO_FINGERPRINT, VISUAL_HASH, STRUCTURAL, CROSS_FILE }

@Serializable
enum class AnalysisTrack { AUDIO, VIDEO, STRUCTURAL }

@Serializable
enum class FindingCategory(val label: String) {
    REPEATED_AUDIO("Repeated audio"),
    SIMILAR_AUDIO("Similar audio"),
    REPEATED_VISUALS("Repeated visuals"),
    SIMILAR_VISUALS("Similar visuals"),
    STRUCTURAL_ANOMALY("Structural anomaly"),
    CROSS_FILE_SIMILARITY("Cross-file similarity"),
    UNVERIFIED_COPYRIGHT_CANDIDATE("Unverified copyright candidate"),
    INCONCLUSIVE("Inconclusive"),
    NO_MATCH("No match found in analyzed set"),
}

@Serializable
enum class FindingStatus { CONFIRMED, SUSPECTED, INCONCLUSIVE }

@Serializable
data class Evidence(
    val description: String,
    val value: Double? = null,
    val unit: String? = null,
    val details: String? = null,
)

@Serializable
data class Finding(
    val id: String,
    val category: FindingCategory,
    val status: FindingStatus,
    val track: AnalysisTrack,
    val method: AnalysisMethod,
    val range: TimeRange,
    val confidence: Float,
    val confidenceExplanation: String,
    val evidence: List<Evidence>,
    val comparisonSource: String? = null,
    val note: String? = null,
) {
    val durationUs: Long get() = range.durationUs
    val startUs: Long get() = range.startUs
    val endUs: Long get() = range.endUs
}

@Serializable
data class CoverageSegment(
    val range: TimeRange,
    val analyzed: Boolean,
    val note: String? = null,
)

@Serializable
data class AnalysisCoverage(
    val mediaDurationUs: Long,
    val analyzedSegments: List<CoverageSegment>,
    val unanalyzedRanges: List<TimeRange>,
    val droppedTracks: List<String>,
    val partialDecode: Boolean,
) {
    val analyzedFraction: Float
        get() {
            if (mediaDurationUs <= 0L) return 0f
            val coveredUs = analyzedSegments.filter { it.analyzed }
                .sumOf { it.range.intersectionLength(TimeRange(0, mediaDurationUs)) }
            return (coveredUs.toFloat() / mediaDurationUs.toFloat()).coerceIn(0f, 1f)
        }
}

@Serializable
data class ProposedEdit(
    val id: String,
    val range: TimeRange,
    val track: AnalysisTrack,
    val findingIds: List<String>,
    val operation: ProposedOperation,
)

@Serializable
enum class ProposedOperation { REMOVE_AUDIO, REMOVE_AUDIO_VIDEO, REPLACE, REVIEW }

@Serializable
data class ScanReport(
    val mediaId: String,
    val mediaName: String,
    val scannedAtEpochMs: Long,
    val mediaDurationUs: Long,
    val methodsUsed: List<AnalysisMethod>,
    val coverage: AnalysisCoverage,
    val findings: List<Finding>,
    val proposedEdits: List<ProposedEdit>,
    val warnings: List<String>,
    val limitations: List<String>,
    val settings: ScanSettingsSnapshot,
) {
    val hasFindings: Boolean get() = findings.isNotEmpty()
}

@Serializable
data class ScanSettingsSnapshot(
    val windowSeconds: Int,
    val sampling: SamplingIntensity,
    val referenceFiles: List<String>,
    val lowResourceMode: Boolean,
    val compareAudio: Boolean,
    val compareVisual: Boolean,
    val minConfidence: Float,
)

@Serializable
enum class SamplingIntensity(val label: String, val stepSeconds: Int, val audioChunk: Int) {
    LOW("Low", 8, 2048),
    MEDIUM("Medium", 4, 4096),
    HIGH("High", 2, 8192),
}

fun TimeRange.intersectionLength(other: TimeRange): Long =
    (minOf(endUs, other.endUs) - maxOf(startUs, other.startUs)).coerceAtLeast(0L)
