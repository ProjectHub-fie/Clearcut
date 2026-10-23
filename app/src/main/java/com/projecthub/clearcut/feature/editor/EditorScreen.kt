package com.projecthub.clearcut.feature.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projecthub.clearcut.core.designsystem.ClearCutColors
import com.projecthub.clearcut.core.designsystem.PremiumCard
import com.projecthub.clearcut.core.media.MediaPreview
import com.projecthub.clearcut.core.model.AnalysisTrack
import com.projecthub.clearcut.core.model.EditOperationType
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.SyncPolicy
import com.projecthub.clearcut.core.model.TimeRange
import com.projecthub.clearcut.core.model.ValidationStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorRoute(
    mediaUri: String,
    onBack: () -> Unit,
    vm: EditorViewModel = viewModel(factory = EditorViewModel.Factory),
) {
    val uiState by vm.uiState.collectAsState()
    val media by vm.media.collectAsState()
    var showCutDialog by remember { mutableStateOf(false) }
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }

    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext

    LaunchedEffect(mediaUri) {
        if (media == null && mediaUri.isNotBlank()) {
            val parsed = Uri.parse(mediaUri)
            val name = parsed.lastPathSegment ?: "media"
            val inspected = runCatching {
                com.projecthub.clearcut.core.media.MediaInspector(appContext)
                    .inspect(parsed, com.projecthub.clearcut.core.model.MediaSource.LOCAL, name)
            }.getOrNull()
            if (inspected != null) {
                vm.setMedia(inspected)
            } else {
                vm.setMedia(
                    MediaInfo(uri = mediaUri, displayName = name, source = com.projecthub.clearcut.core.model.MediaSource.LOCAL)
                )
            }
        }
    }

    val edl = uiState.edl

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Copyright Removal Lab") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                // Warnings banner
                PremiumCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Row(Modifier.padding(14.dp)) {
                        Icon(Icons.Filled.Verified, contentDescription = null, tint = ClearCutColors.Warning)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Experimental technical editing tool for media you own or are authorized to modify. " +
                                "It is not intended to evade platform copyright detection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClearCutColors.Warning,
                        )
                    }
                }
            }

            item {
                media?.let { m ->
                    if (m.durationUs > 0) {
                        MediaPreview(
                            mediaUri = m.uri,
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                        )
                    }
                }
                Text("Original media is always preserved. Edits are applied only on export.", style = MaterialTheme.typography.bodySmall, color = ClearCutColors.OnInkDim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }

            // Toolbar
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { showCutDialog = true },
                        enabled = media != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cut range")
                    }
                    OutlinedButton(
                        onClick = { vm.undo() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Undo")
                    }
                }
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.clearAll() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear edits")
                    }
                    OutlinedButton(
                        onClick = { /* preview uses EDL evaluator below */ },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.edl?.operations?.isNotEmpty() == true,
                    ) {
                        Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Validate EDL")
                    }
                }
            }

            item {
                if (edl != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Expected output duration: ${TimeRange.formatUs(uiState.outputDurationUs)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = ClearCutColors.Accent,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    uiState.validationWarnings.forEach {
                        Text("⚠ $it", style = MaterialTheme.typography.labelSmall, color = ClearCutColors.Warning, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    Spacer(Modifier.height(8.dp))

                    if (edl.operations.isNotEmpty()) {
                        Text("Edit Decision List", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
                        Spacer(Modifier.height(6.dp))
                    }
                    edl.operations.forEach { op ->
                        OperationRow(
                            opId = op.id,
                            opType = op.operationType,
                            range = op.sourceRange,
                            track = op.track,
                            syncPolicy = op.syncPolicy,
                            crossfadeMs = op.crossfadeMs,
                            replacementUri = op.replacementSourceUri,
                            status = op.validationStatus,
                            onRemove = { vm.removeOperation(op.id) },
                            onSelect = { vm.selectOperation(op.id) },
                        )
                    }

                    // Export button
                    if (edl.operations.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { /* navigate handled by caller */ },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            enabled = uiState.validationWarnings.none { it.contains("empty output") },
                        ) {
                            Text("Export with EDL")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Export builds an MP4 (H.264/AAC) by cutting and joining the marked ranges. Audio is never muted and video is never blurred.",
                            style = MaterialTheme.typography.labelSmall,
                            color = ClearCutColors.OnInkDim,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }

    if (showCutDialog && media != null) {
        CutRangeDialog(
            durationUs = media!!.durationUs,
            startText = startText,
            endText = endText,
            onStartChange = { startText = it },
            onEndChange = { endText = it },
            onConfirm = { startUs, endUs, track, sync ->
                vm.cutRange(TimeRange(startUs, endUs), track, sync)
                startText = ""
                endText = ""
                showCutDialog = false
            },
            onDismiss = { showCutDialog = false },
        )
    }
}

@Composable
private fun OperationRow(
    opId: String,
    opType: EditOperationType,
    range: TimeRange,
    track: AnalysisTrack,
    syncPolicy: SyncPolicy,
    crossfadeMs: Int,
    replacementUri: String?,
    status: ValidationStatus,
    onRemove: () -> Unit,
    onSelect: () -> Unit,
) {
    PremiumCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).clickable(onClick = onSelect)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${opType.name}: ${TimeRange.formatUs(range.startUs)} → ${TimeRange.formatUs(range.endUs)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Track: ${track.name.lowercase()} · sync: ${syncPolicy.name.replace("_", " ").lowercase()} · crossfade: ${crossfadeMs}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClearCutColors.OnInkDim,
                )
                if (replacementUri != null) {
                    Text("Replacement: ${replacementUri.substringAfterLast('/')}", style = MaterialTheme.typography.labelSmall, color = ClearCutColors.Teal)
                }
                Text(
                    "Validation: ${status.name.lowercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status == ValidationStatus.VALID) ClearCutColors.Success else ClearCutColors.Warning,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove operation", tint = ClearCutColors.Error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CutRangeDialog(
    durationUs: Long,
    startText: String,
    endText: String,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onConfirm: (Long, Long, AnalysisTrack, SyncPolicy) -> Unit,
    onDismiss: () -> Unit,
) {
    var track by remember { mutableStateOf(AnalysisTrack.AUDIO) }
    var sync by remember { mutableStateOf(SyncPolicy.NONE) }

    fun parseSeconds(text: String): Long? {
        val parts = text.split(":")
        return try {
            when (parts.size) {
                1 -> (parts[0].toDouble() * 1_000_000).toLong()
                2 -> ((parts[0].toDouble() * 60) + parts[1].toDouble()).toLong() * 1_000_000
                3 -> ((parts[0].toDouble() * 3600) + (parts[1].toDouble() * 60) + parts[2].toDouble()).toLong() * 1_000_000
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cut a range") },
        text = {
            Column {
                Text("Enter start and end as seconds (e.g. 12.5) or mm:ss. Max duration ${TimeRange.formatUs(durationUs)}.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = startText, onValueChange = onStartChange, label = { Text("Start") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = endText, onValueChange = onEndChange, label = { Text("End") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Tracks to cut:", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackChip("Audio", track == AnalysisTrack.AUDIO) { track = AnalysisTrack.AUDIO }
                    TrackChip("Video", track == AnalysisTrack.VIDEO) { track = AnalysisTrack.VIDEO }
                    TrackChip("Audio+Video", track == AnalysisTrack.STRUCTURAL) { track = AnalysisTrack.STRUCTURAL }
                }
                Spacer(Modifier.height(12.dp))
                val start = parseSeconds(startText)
                val end = parseSeconds(endText)
                val valid = start != null && end != null && start >= 0 && end > start && end <= durationUs
                if (start != null && end != null && !valid) {
                    Text("Invalid range: must fall within 0 – ${TimeRange.formatUs(durationUs)}.", style = MaterialTheme.typography.labelSmall, color = ClearCutColors.Error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val start = parseSeconds(startText)!!
                    val end = parseSeconds(endText)!!
                    onConfirm(start, end, track, sync)
                },
                enabled = parseSeconds(startText)?.let { s -> parseSeconds(endText)?.let { e -> e > s && e <= durationUs } } == true,
            ) { Text("Cut") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TrackChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}