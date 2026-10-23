package com.projecthub.clearcut.core.report

import com.projecthub.clearcut.core.model.Edl
import com.projecthub.clearcut.core.model.EdlOperation
import com.projecthub.clearcut.core.model.Finding
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.ScanReport
import com.projecthub.clearcut.core.model.TimeRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates shareable TXT and JSON reports with full transparency about
 * coverage, methods, findings, evidence, limitations, edits and validation.
 */
object ReportGenerator {

    const val DISCLAIMER =
        "ClearCut provides technical similarity analysis and media editing tools. " +
            "Similarity findings do not establish copyright ownership or infringement. " +
            "No match found does not mean the content is copyright-free. " +
            "This report does not guarantee that YouTube, Facebook, or another platform " +
            "will or will not flag the media."

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true // keep schema + stable fields even at defaults
    }

    @Serializable
    data class ReportJson(
        val schema: String = "clearcut/report/v1",
        val input: InputSection,
        val methodsUsed: List<String>,
        val coverage: CoverageSection,
        val findings: List<FindingSection>,
        val approvedEdits: List<EditSection>,
        val output: OutputSection? = null,
        val knownLimitations: List<String>,
        val disclaimer: String,
    )

    @Serializable
    data class InputSection(
        val filename: String,
        val type: String,
        val mimeType: String?,
        val durationUs: Long,
        val sizeBytes: Long,
        val scanDateEpochMs: Long,
        val scanDate: String,
        val hasAudio: Boolean,
        val hasVideo: Boolean,
    )

    @Serializable
    data class CoverageSection(
        val fractionAnalyzed: Float,
        val analyzedSegments: List<RangeSection>,
        val unanalyzedRanges: List<RangeSection>,
        val droppedTracks: List<String>,
        val partialDecode: Boolean,
    )

    @Serializable
    data class RangeSection(val startUs: Long, val endUs: Long, val start: String, val end: String)

    @Serializable
    data class FindingSection(
        val id: String,
        val category: String,
        val status: String,
        val track: String,
        val method: String,
        val range: RangeSection,
        val confidence: Float,
        val confidenceExplanation: String,
        val evidence: List<EvidenceSection>,
        val comparisonSource: String?,
        val note: String?,
    )

    @Serializable
    data class EvidenceSection(val description: String, val value: Double?, val unit: String?, val details: String?)

    @Serializable
    data class EditSection(
        val id: String,
        val sourceRange: RangeSection,
        val track: String,
        val operationType: String,
        val replacementSource: String?,
        val syncPolicy: String,
        val crossfadeMs: Int,
        val validationStatus: String,
    )

    @Serializable
    data class OutputSection(
        val filePath: String,
        val durationUs: Long,
        val sizeBytes: Long,
        val validationWarnings: List<String>,
        val validated: Boolean,
    )

    fun toJson(
        media: MediaInfo,
        report: ScanReport,
        edl: Edl?,
        output: OutputSection? = null,
    ): String {
        val data = ReportJson(
            input = InputSection(
                filename = media.displayName,
                type = if (media.isAudioOnly) "audio" else "video",
                mimeType = media.mimeType,
                durationUs = media.durationUs,
                sizeBytes = media.sizeBytes,
                scanDateEpochMs = report.scannedAtEpochMs,
                scanDate = formatDate(report.scannedAtEpochMs),
                hasAudio = media.hasAudio,
                hasVideo = media.hasVideo,
            ),
            methodsUsed = report.methodsUsed.map { it.name },
            coverage = CoverageSection(
                fractionAnalyzed = report.coverage.analyzedFraction,
                analyzedSegments = report.coverage.analyzedSegments.map { rangeSection(it.range) },
                unanalyzedRanges = report.coverage.unanalyzedRanges.map { rangeSection(it) },
                droppedTracks = report.coverage.droppedTracks,
                partialDecode = report.coverage.partialDecode,
            ),
            findings = report.findings.map { findingSection(it) },
            approvedEdits = edl?.operations?.map { editSection(it) } ?: emptyList(),
            output = output,
            knownLimitations = report.limitations,
            disclaimer = DISCLAIMER,
        )
        return json.encodeToString(ReportJson.serializer(), data)
    }

    fun toTxt(
        media: MediaInfo,
        report: ScanReport,
        edl: Edl?,
        output: OutputSection? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=".repeat(70))
        sb.appendLine("CLEARCUT MEDIA ANALYSIS REPORT")
        sb.appendLine("=".repeat(70))
        sb.appendLine()
        sb.appendLine("Input")
        sb.appendLine("-".repeat(40))
        sb.appendLine("File         : ${media.displayName}")
        sb.appendLine("Type         : ${if (media.isAudioOnly) "audio" else "video"} (${media.mimeType ?: "unknown"})")
        sb.appendLine("Duration     : ${TimeRange.formatUs(media.durationUs)}")
        sb.appendLine("Size         : ${media.sizeBytes} bytes")
        sb.appendLine("Scanned at   : ${formatDate(report.scannedAtEpochMs)}")
        sb.appendLine("Audio tracks : ${media.audioTracks.size}")
        sb.appendLine("Video tracks : ${media.videoTracks.size}")
        sb.appendLine()
        sb.appendLine("Analysis methods")
        sb.appendLine("-".repeat(40))
        report.methodsUsed.forEach { sb.appendLine("  - ${it.name}") }
        sb.appendLine()
        sb.appendLine("Coverage")
        sb.appendLine("-".repeat(40))
        sb.appendLine("Analyzed fraction : ${(report.coverage.analyzedFraction * 100).toInt()}%")
        report.coverage.unanalyzedRanges.forEach {
            sb.appendLine("  Unanalyzed: ${TimeRange.formatUs(it.startUs)} - ${TimeRange.formatUs(it.endUs)}")
        }
        report.coverage.droppedTracks.forEach { sb.appendLine("  Dropped track: $it") }
        if (report.coverage.partialDecode) sb.appendLine("  NOTE: analysis is partial (decoding failed partway).")
        sb.appendLine()
        sb.appendLine("Findings (${report.findings.size})")
        sb.appendLine("-".repeat(40))
        if (report.findings.isEmpty()) {
            sb.appendLine("No similarity findings above the configured threshold in the analyzed reference set.")
        } else {
            report.findings.forEachIndexed { idx, f ->
                sb.appendLine("[${idx + 1}] ${f.category.label}")
                sb.appendLine("    Status      : ${f.status}")
                sb.appendLine("    Track       : ${f.track}")
                sb.appendLine("    Range       : ${TimeRange.formatUs(f.startUs)} - ${TimeRange.formatUs(f.endUs)} (${TimeRange.formatUs(f.durationUs)})")
                sb.appendLine("    Confidence  : ${"%.2f".format(f.confidence)} (${f.confidenceExplanation})")
                f.evidence.forEach { e ->
                    sb.appendLine("    Evidence    : ${e.description}" + (e.value?.let { " = $it ${e.unit ?: ""}" } ?: ""))
                }
                f.comparisonSource?.let { sb.appendLine("    Compared to : $it") }
                f.note?.let { sb.appendLine("    Note        : $it") }
            }
        }
        sb.appendLine()
        sb.appendLine("Approved edits")
        sb.appendLine("-".repeat(40))
        if (edl == null || edl.operations.isEmpty()) {
            sb.appendLine("No edits were approved for this report.")
        } else {
            edl.operations.forEach { op ->
                sb.appendLine("  ${op.operationType} ${op.track} ${TimeRange.formatUs(op.sourceRange.startUs)}-${TimeRange.formatUs(op.sourceRange.endUs)} " +
                    "replacement=${op.replacementSourceUri ?: "none"} sync=${op.syncPolicy} crossfade=${op.crossfadeMs}ms " +
                    "validation=${op.validationStatus}")
            }
        }
        sb.appendLine()
        sb.appendLine("Output")
        sb.appendLine("-".repeat(40))
        if (output == null) {
            sb.appendLine("No export was produced for this report.")
        } else {
            sb.appendLine("File        : ${output.filePath}")
            sb.appendLine("Duration    : ${TimeRange.formatUs(output.durationUs)}")
            sb.appendLine("Size        : ${output.sizeBytes} bytes")
            sb.appendLine("Validated   : ${output.validated}")
            output.validationWarnings.forEach { sb.appendLine("  Warning: $it") }
        }
        sb.appendLine()
        sb.appendLine("Known limitations")
        sb.appendLine("-".repeat(40))
        report.limitations.forEach { sb.appendLine("  - $it") }
        if (report.limitations.isEmpty()) sb.appendLine("  (none reported)")
        sb.appendLine()
        sb.appendLine(DISCLAIMER)
        return sb.toString()
    }

    private fun rangeSection(r: TimeRange): RangeSection =
        RangeSection(r.startUs, r.endUs, TimeRange.formatUs(r.startUs), TimeRange.formatUs(r.endUs))

    private fun findingSection(f: Finding): FindingSection = FindingSection(
        id = f.id,
        category = f.category.label,
        status = f.status.name,
        track = f.track.name,
        method = f.method.name,
        range = rangeSection(f.range),
        confidence = f.confidence,
        confidenceExplanation = f.confidenceExplanation,
        evidence = f.evidence.map { EvidenceSection(it.description, it.value, it.unit, it.details) },
        comparisonSource = f.comparisonSource,
        note = f.note,
    )

    private fun editSection(op: EdlOperation): EditSection = EditSection(
        id = op.id,
        sourceRange = rangeSection(op.sourceRange),
        track = op.track.name,
        operationType = op.operationType.name,
        replacementSource = op.replacementSourceUri,
        syncPolicy = op.syncPolicy.name,
        crossfadeMs = op.crossfadeMs,
        validationStatus = op.validationStatus.name,
    )

    private fun formatDate(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

    /** Write to disk. */
    fun writeFile(dir: File, baseName: String, content: String, extension: String): File {
        dir.mkdirs()
        val file = File(dir, "${sanitize(baseName)}_clearcut.$extension")
        file.writeText(content)
        return file
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "media" }
}