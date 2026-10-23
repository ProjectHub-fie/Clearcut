package com.projecthub.clearcut.feature.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import com.projecthub.clearcut.core.designsystem.ClearCutColors
import com.projecthub.clearcut.core.designsystem.ProcessingStateView
import com.projecthub.clearcut.core.designsystem.PremiumCard
import com.projecthub.clearcut.core.designsystem.ScanProgressIndicator
import com.projecthub.clearcut.core.media.MediaPreview
import com.projecthub.clearcut.core.model.AnalysisTrack
import com.projecthub.clearcut.core.model.Finding
import com.projecthub.clearcut.core.model.FindingCategory
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.OperationState
import com.projecthub.clearcut.core.model.SamplingIntensity
import com.projecthub.clearcut.core.model.ScanReport
import com.projecthub.clearcut.core.model.ScanSettingsSnapshot
import com.projecthub.clearcut.core.model.TimeRange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerRoute(
    mediaUri: String,
    onBack: () -> Unit,
    onOpenEditor: (String) -> Unit,
    vm: ScannerViewModel = viewModel(factory = ScannerViewModel.Factory),
) {
    val state by vm.scanState.collectAsState()
    val media by vm.media.collectAsState()
    val seekRequest by vm.seekRequest.collectAsState()
    val filter by vm.filterCategory.collectAsState()
    val selectedId by vm.selectedFindingId.collectAsState()

    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext

    LaunchedEffect(mediaUri) {
        if (vm.media.value == null && mediaUri.isNotBlank()) {
            val parsed = android.net.Uri.parse(mediaUri)
            val name = parsed.lastPathSegment ?: "media"
            vm.setMedia(
                com.projecthub.clearcut.core.model.MediaInfo(
                    uri = mediaUri,
                    displayName = name,
                    source = com.projecthub.clearcut.core.model.MediaSource.LOCAL,
                )
            )
            // Re-inspect async so full-duration scanning works after process recreation.
            val inspected = runCatching {
                com.projecthub.clearcut.core.media.MediaInspector(appContext)
                    .inspect(parsed, com.projecthub.clearcut.core.model.MediaSource.LOCAL, name)
            }.getOrNull()
            inspected?.let { vm.setMedia(it) }
        }
    }

    val report = vm.currentReport()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scanner") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                // Preview
                media?.let { m ->
                    if (m.uri.isNotBlank() && m.durationUs > 0) {
                        MediaPreview(
                            mediaUri = m.uri,
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            onPositionChange = { vm.setPreviewPosition(it) },
                            seekRequestUs = seekRequest,
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().height(120.dp).background(ClearCutColors.InkRaised),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Movie, contentDescription = null, tint = ClearCutColors.OnInkDim)
                                Spacer(Modifier.height(6.dp))
                                Text(media?.displayName ?: "media", style = MaterialTheme.typography.bodySmall, color = ClearCutColors.OnInkDim)
                            }
                        }
                    }
                }
                media?.let { FileInfoBar(it) }
            }

            // Scan controls
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Button(
                        onClick = { vm.startScan(vm.currentSettingsOrDefault()) },
                        enabled = !state.isActive && state !is OperationState.Completed,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Scan full duration")
                    }
                    if (state.isActive) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.cancelScan() }) { Text("Cancel") }
                    }
                }
            }

            // Processing state
            when (val s = state) {
                is OperationState.InProgress -> item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ScanProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text(s.phase, style = MaterialTheme.typography.titleSmall)
                    }
                }
                is OperationState.Failed -> item {
                    ProcessingStateView(
                        state = s,
                        onRetry = { vm.startScan(vm.currentSettingsOrDefault()) },
                        onDismissError = { /* keep */ },
                    )
                }
                is OperationState.Cancelled -> item {
                    Text(
                        "Scan cancelled. Findings below are from the last run (if any).",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClearCutColors.Warning,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                else -> Unit
            }

            // Report results
            if (report != null) {
                item {
                    ReportHeader(report)
                }
                item {
                    if (report.findings.isNotEmpty()) {
                        FindingToolbar(filter = filter, onFilter = vm::setFilter)
                    }
                }
                val filtered = report.findings.filter { f -> filter == null || f.category.name == filter || f.category.label == filter }

                if (filtered.isEmpty() && report.findings.isNotEmpty()) {
                    item {
                        Text("No findings match this filter.", style = MaterialTheme.typography.bodySmall, color = ClearCutColors.OnInkDim, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }

                if (report.findings.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.SearchOff, contentDescription = null, tint = ClearCutColors.OnInkFaint, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("No similarity findings", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "No repeated or similar passages were found above the threshold in the analyzed reference set. This does NOT mean the content is copyright-free.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ClearCutColors.OnInkDim,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                } else {
                    item {
                        AnalysisTimeline(report = report, selectedId = selectedId, onSelectId = vm::selectFinding)
                    }
                    items(filtered, key = { "f${it.id}" }) { finding ->
                        FindingRow(
                            finding = finding,
                            selected = selectedId == finding.id,
                            onClick = { vm.selectFinding(finding.id) },
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.padding(horizontal = 20.dp)) {
                        OutlinedButton(
                            onClick = { media?.let { onOpenEditor(it.uri) } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            Text("Open in Editor")
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

fun ScannerViewModel.currentSettingsOrDefault(): ScanSettingsSnapshot = ScanSettingsSnapshot(
    windowSeconds = 20,
    sampling = SamplingIntensity.MEDIUM,
    referenceFiles = emptyList(),
    lowResourceMode = false,
    compareAudio = true,
    compareVisual = true,
    minConfidence = 0.5f,
)

@Composable
private fun FileInfoBar(info: MediaInfo) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoPill("Duration", TimeRange.formatUs(info.durationUs))
        if (info.width > 0) InfoPill("Resolution", "${info.width}×${info.height}")
        if (info.frameRate > 0) InfoPill("FPS", String.format(Locale.US, "%.1f", info.frameRate))
        InfoPill("Tracks", "${info.audioTracks.size}A / ${info.videoTracks.size}V")
    }
}

@Composable
private fun InfoPill(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = ClearCutColors.OnInkFaint)
        Text(value, style = MaterialTheme.typography.labelLarge, color = ClearCutColors.OnInk)
    }
}

@Composable
private fun ReportHeader(report: ScanReport) {
    PremiumCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Analysis complete", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            val labels = report.methodsUsed.joinToString(" · ") { it.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() } }
            Text("Methods: $labels", style = MaterialTheme.typography.bodySmall, color = ClearCutColors.OnInkDim)
            Spacer(Modifier.height(4.dp))
            Text(
                "Coverage: ${(report.coverage.analyzedFraction * 100).toInt()}% · ${report.findings.size} finding(s)",
                style = MaterialTheme.typography.bodySmall,
                color = ClearCutColors.OnInkDim,
            )
            if (report.coverage.unanalyzedRanges.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                report.coverage.unanalyzedRanges.take(3).forEach {
                    Text(
                        "- Unscanned: ${TimeRange.formatUs(it.startUs)} – ${TimeRange.formatUs(it.endUs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClearCutColors.Warning,
                    )
                }
            }
            if (report.limitations.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                report.limitations.forEach {
                    Text("⚠ ${it}", style = MaterialTheme.typography.labelSmall, color = ClearCutColors.Warning)
                }
            }
        }
    }
}

@Composable
private fun FindingToolbar(filter: String?, onFilter: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val categories = listOf("All") + FindingCategory.entries.map { it.name }
        categories.forEach { c ->
            FilterChip(
                selected = if (c == "All") filter == null else filter == c,
                onClick = { onFilter(if (c == "All") null else c) },
                label = { Text(if (c == "All") "All" else FindingCategory.valueOf(c).label) },
            )
        }
    }
}

/** Three synchronized timeline lanes (audio, video, structural). */
@Composable
private fun AnalysisTimeline(
    report: ScanReport,
    selectedId: String?,
    onSelectId: (String) -> Unit,
) {
    val durationUs = report.mediaDurationUs.coerceAtLeast(1L)
    val widthPx = 320f
    PremiumCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Timeline", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            TimelineLane(
                label = "Audio",
                laneColor = ClearCutColors.RepeatedAudio,
                findings = report.findings.filter { it.track == AnalysisTrack.AUDIO },
                durationUs = durationUs,
                widthPx = widthPx,
                selectedId = selectedId,
                onSelectId = onSelectId,
            )
            Spacer(Modifier.height(6.dp))
            TimelineLane(
                label = "Video",
                laneColor = ClearCutColors.RepeatedVisual,
                findings = report.findings.filter { it.track == AnalysisTrack.VIDEO },
                durationUs = durationUs,
                widthPx = widthPx,
                selectedId = selectedId,
                onSelectId = onSelectId,
            )
            Spacer(Modifier.height(6.dp))
            TimelineLane(
                label = "Structure",
                laneColor = ClearCutColors.Structural,
                findings = report.findings.filter { it.track == AnalysisTrack.STRUCTURAL },
                durationUs = durationUs,
                widthPx = widthPx,
                selectedId = selectedId,
                onSelectId = onSelectId,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Lane colors: violet = repeated audio · teal = repeated visual · amber = structural. Findings shown are technical observations only.",
                style = MaterialTheme.typography.labelSmall,
                color = ClearCutColors.OnInkDim,
            )
        }
    }
}

@Composable
private fun TimelineLane(
    label: String,
    laneColor: Color,
    findings: List<Finding>,
    durationUs: Long,
    widthPx: Float,
    selectedId: String?,
    onSelectId: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = ClearCutColors.OnInkDim, modifier = Modifier.width(64.dp))
        Box(
            Modifier
                .weight(1f)
                .height(18.dp)
                .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(6.dp))
                .semantics { contentDescription = "Timeline lane $label" },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val scale = this.size.width / durationUs.toFloat()
                findings.forEach { f ->
                    val x = f.startUs * scale
                    val w = (f.durationUs * scale).coerceAtLeast(4f)
                    val color = if (f.id == selectedId) Color.White else laneColor.copy(alpha = if (f.status.name == "CONFIRMED") 0.95f else 0.5f)
                    drawRect(color = color, topLeft = Offset(x, 2f), size = Size(w, size.height - 4f), style = Stroke(3f))
                }
            }
        }
    }
}

@Composable
private fun FindingRow(finding: Finding, selected: Boolean, onClick: () -> Unit) {
    val color = findingCategoryColor(finding.category)
    PremiumCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clickable { onClick() },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).background(color, RoundedCornerShape(5.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(finding.category.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        finding.status.name.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (finding.status.name == "CONFIRMED") ClearCutColors.Success else ClearCutColors.Warning,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${TimeRange.formatUs(finding.startUs)} → ${TimeRange.formatUs(finding.endUs)} · ${TimeRange.formatUs(finding.durationUs)} · ${finding.track.name.lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClearCutColors.OnInkDim,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    finding.confidenceExplanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = ClearCutColors.OnInkDim,
                    maxLines = 3,
                )
                if (finding.evidence.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    finding.evidence.take(2).forEach { e ->
                        val valueText = e.value?.let { trimTrailingZero(it) + (e.unit?.let { " $it" } ?: "") }
                        Text(
                            "• ${e.description}" + (valueText?.let { " = $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = ClearCutColors.OnInkFaint,
                            maxLines = 1,
                        )
                    }
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open", tint = ClearCutColors.OnInkFaint)
        }
    }
}

private fun trimTrailingZero(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

private fun findingCategoryColor(category: FindingCategory): Color = when (category) {
    FindingCategory.REPEATED_AUDIO -> ClearCutColors.RepeatedAudio
    FindingCategory.SIMILAR_AUDIO -> ClearCutColors.SimilarAudio
    FindingCategory.REPEATED_VISUALS -> ClearCutColors.RepeatedVisual
    FindingCategory.SIMILAR_VISUALS -> ClearCutColors.SimilarVisual
    FindingCategory.STRUCTURAL_ANOMALY -> ClearCutColors.Structural
    FindingCategory.CROSS_FILE_SIMILARITY -> ClearCutColors.CrossFile
    FindingCategory.UNVERIFIED_COPYRIGHT_CANDIDATE -> ClearCutColors.Warning
    FindingCategory.INCONCLUSIVE -> ClearCutColors.OnInkDim
    FindingCategory.NO_MATCH -> ClearCutColors.Success
}