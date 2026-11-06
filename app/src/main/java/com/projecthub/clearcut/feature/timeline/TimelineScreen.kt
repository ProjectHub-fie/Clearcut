package com.projecthub.clearcut.feature.timeline

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projecthub.clearcut.core.designsystem.ClearCutColors
import com.projecthub.clearcut.core.designsystem.PremiumCard
import com.projecthub.clearcut.core.media.MediaPreview
import com.projecthub.clearcut.core.model.ClipKind
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.OperationState
import com.projecthub.clearcut.core.model.TimelineClip
import com.projecthub.clearcut.core.model.TimelineTrack
import com.projecthub.clearcut.core.model.TrackKind
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineRoute(
    mediaUri: String,
    onBack: () -> Unit,
    onOpenEffectDemo: (String) -> Unit = {},
    vm: TimelineViewModel = viewModel(factory = TimelineViewModel.Factory),
) {
    val uiState by vm.uiState.collectAsState()
    val voice by vm.voiceoverState.collectAsState()
    val context = LocalContext.current.applicationContext

    // Media pickers
    var pendingKind by remember { mutableStateOf<ClipKind?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingKind?.let { kind ->
            vm.addMediaClip(
                uri,
                uri.lastPathSegment ?: "media",
                kind,
                when (kind) {
                    ClipKind.VIDEO, ClipKind.IMAGE, ClipKind.PICTURE_OVERLAY -> TrackKind.VIDEO
                    ClipKind.AUDIO -> TrackKind.MUSIC
                    else -> TrackKind.OVERLAY
                },
            )
            pendingKind = null
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording()
    }

    fun requestPick(kind: ClipKind) {
        pendingKind = kind
        picker.launch(
            arrayOf(
                "video/*",
                "image/*",
                "audio/*",
            )
        )
    }

    LaunchedEffect(Unit) {
        val pending = vm.consumePendingPick()
        if (pending != null) pendingKind = pending
    }

    // Inspect the media once when entering.
    LaunchedEffect(mediaUri) {
        if (uiState.timeline == null && mediaUri.isNotBlank()) {
            val parsed = Uri.parse(mediaUri)
            val inspected = runCatching {
                com.projecthub.clearcut.core.media.MediaInspector(context)
                    .inspect(parsed, com.projecthub.clearcut.core.model.MediaSource.LOCAL, parsed.lastPathSegment ?: "media")
            }.getOrNull()
            val mediaInfo = inspected
                ?: MediaInfo(
                    uri = mediaUri,
                    displayName = parsed.lastPathSegment ?: "media",
                    source = com.projecthub.clearcut.core.model.MediaSource.LOCAL,
                )
            vm.open(mediaInfo)
        }
    }

    // Drive the playhead while playing (uses the raw ExoPlayer preview which
    // shows the source; the position is still used to highlight the timeline).
    LaunchedEffect(uiState.isPlaying) {
        if (!uiState.isPlaying) return@LaunchedEffect
        while (true) {
            delay(1000 / 30L)
            vm.movePlayhead(uiState.playheadUs + 33_000L)
            if (uiState.playheadUs >= (uiState.timeline?.durationUs ?: 0L)) {
                vm.pause()
                break
            }
        }
    }

    // Recording metering ticker.
    LaunchedEffect(uiState.recording) {
        while (uiState.recording) {
            vm.tickRecording()
            delay(200)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Timeline Studio") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.persist() }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save project")
                    }
                    IconButton(onClick = { vm.movePlayhead(0L) }) {
                        Icon(Icons.Filled.Replay, contentDescription = "Restart")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val timeline = uiState.timeline
        if (timeline == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            // ---- Preview ----
            val sourceUri = timeline.sourceUri
            if (sourceUri != null) {
                MediaPreview(
                    mediaUri = sourceUri,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(140.dp).background(MaterialTheme.colorScheme.surface)
                        .clip(RoundedCornerShape(12.dp)).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No primary media", style = MaterialTheme.typography.titleSmall)
                        Text("Add a video/image below.", style = MaterialTheme.typography.bodySmall, color = ClearCutColors.OnInkDim)
                    }
                }
            }

            // Transport
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.movePlayhead((uiState.playheadUs - 1_000_000L).coerceAtLeast(0)) }) {
                    Icon(Icons.Filled.FastRewind, contentDescription = "Back 1s")
                }
                IconButton(onClick = { if (uiState.isPlaying) vm.pause() else vm.play() }, modifier = Modifier.size(52.dp)) {
                    Icon(
                        if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(40.dp),
                        tint = ClearCutColors.Accent,
                    )
                }
                IconButton(onClick = { vm.movePlayhead(uiState.playheadUs + 1_000_000L) }) {
                    Icon(Icons.Filled.FastForward, contentDescription = "Forward 1s")
                }
                Text(
                    "/ ${com.projecthub.clearcut.core.model.TimeRange.formatUs(timeline.durationUs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = ClearCutColors.OnInkDim,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    com.projecthub.clearcut.core.model.TimeRange.formatUs(uiState.playheadUs),
                    style = MaterialTheme.typography.labelLarge,
                    color = ClearCutColors.Accent,
                )
            }

            // Time ruler
            TimeRuler(timeline = timeline, playheadUs = uiState.playheadUs)

            // ---- Option panel for the selected clip ----
            val selected = uiState.selectedClipId?.let { timeline.clip(it) }
            if (selected != null) {
                ClipOptionsPanel(selected, vm)
            } else {
                TimelineToolbar(
                    timeline = timeline,
                    vm = vm,
                    onRequestPick = ::requestPick,
                )
            }

            // Warnings
            uiState.rendererWarnings.take(2).forEach {
                Text(
                    "⚠ ${it}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClearCutColors.Warning,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // ---- Timeline lanes ----
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                items(timeline.tracks, key = { it.id }) { track ->
                    TrackLane(
                        track = track,
                        timeline = timeline,
                        isSelected = uiState.selectedClipId?.let { timeline.clip(it)?.trackId } == track.id,
                        selectedClipId = uiState.selectedClipId,
                        onSelectClip = vm::select,
                        onAdd = {
                        val clipKind = trackKindToClipKind(track.kind)
                        if (clipKind == ClipKind.TEXT) vm.addTextClip()
                        else requestPick(clipKind)
                    },
                        onToggleMute = { vm.toggleTrackMute(track.id) },
                        onToggleLock = { vm.toggleTrackLock(track.id) },
                        onMoveClip = { clipId, dUs -> vm.moveClipBy(clipId, dUs) },
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Bottom action bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (uiState.recording) {
                    Button(
                        onClick = { vm.stopRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = ClearCutColors.Error),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop · ${(voice as? com.projecthub.clearcut.core.media.VoiceoverRecorder.State.Recording)?.elapsedMs?.div(1000) ?: 0}s")
                    }
                } else {
                    OutlinedButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            vm.startRecording()
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Voiceover")
                    }
                }
                Button(onClick = { vm.export() }, modifier = Modifier.weight(1.4f), enabled = timeline.sourceUri != null) {
                    Icon(Icons.Filled.PlayCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Export")
                }
            }
        }
    }

    // Export result dialog.
    val export = uiState.exportState
    if (export is OperationState.Completed) {
        ExportResultDialog(export.value, warnings = emptyList(), onDismiss = vm::resetExport)
    } else if (export is OperationState.CompletedWithWarnings) {
        ExportResultDialog(export.value, warnings = export.warnings, onDismiss = vm::resetExport)
    } else if (export is OperationState.Failed) {
        ExportFailedDialog(export.error.message, onDismiss = vm::resetExport)
    } else if (export is OperationState.Cancelled) {
        ExportFailedDialog("Export was cancelled.", onDismiss = vm::resetExport)
    }
}

private fun trackKindToClipKind(kind: TrackKind): ClipKind = when (kind) {
    TrackKind.VIDEO -> ClipKind.VIDEO
    TrackKind.OVERLAY -> ClipKind.PICTURE_OVERLAY
    TrackKind.TEXT -> ClipKind.TEXT
    TrackKind.AUDIO, TrackKind.MUSIC -> ClipKind.AUDIO
    TrackKind.VOICEOVER -> ClipKind.VOICEOVER
}

@Composable
private fun ExportResultDialog(path: String, warnings: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (warnings.isEmpty()) "Export complete" else "Export complete with notes") },
        text = {
            Column {
                Text("Saved to:\n${path.substringAfterLast('/')}", style = MaterialTheme.typography.bodySmall)
                warnings.forEach {
                    Text("⚠ $it", style = MaterialTheme.typography.labelSmall, color = ClearCutColors.Warning)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ExportFailedDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export failed") },
        text = { Text(message, style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

// ---- Time ruler -------------------------------------------------------------------------------

@Composable
private fun TimeRuler(timeline: com.projecthub.clearcut.core.model.ProjectTimeline, playheadUs: Long) {
    val totalUs = timeline.durationUs.coerceAtLeast(1L)
    Box(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            (0..10).forEach {
                Text(
                    com.projecthub.clearcut.core.model.TimeRange.formatUs(it * totalUs / 10),
                    style = MaterialTheme.typography.labelSmall,
                    color = ClearCutColors.OnInkFaint,
                )
            }
        }
        val fraction = (playheadUs.toFloat() / totalUs).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(2.dp)
                .align(Alignment.BottomStart)
                .background(ClearCutColors.Accent),
        )
    }
}

// ---- Track lane -------------------------------------------------------------------------------

@Composable
private fun TrackLane(
    track: TimelineTrack,
    timeline: com.projecthub.clearcut.core.model.ProjectTimeline,
    isSelected: Boolean,
    selectedClipId: String?,
    onSelectClip: (String?) -> Unit,
    onAdd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
    onMoveClip: (String, Long) -> Unit,
) {
    val totalUs = timeline.durationUs.coerceAtLeast(1L)
    PremiumCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(90.dp)) {
                Text(track.name, style = MaterialTheme.typography.labelLarge,
                    color = if (track.locked) ClearCutColors.OnInkFaint else ClearCutColors.OnInk,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.locked) Text("locked", style = MaterialTheme.typography.labelSmall, color = ClearCutColors.Warning)
            }
            // Clips row — BoxWithConstraints enables fractional absolute placement.
            BoxWithConstraints(Modifier.weight(1f).height(48.dp)) {
                val widthDp = maxWidth
                track.clips.forEach { clip ->
                    val startFraction = (clip.startUs.toFloat() / totalUs).coerceIn(0f, 0.999f)
                    val widthFraction = ((clip.endUs - clip.startUs).coerceAtLeast(1L).toFloat() / totalUs).coerceIn(0.04f, 1f)
                    val color = when (clip.kind) {
                        ClipKind.VIDEO -> ClearCutColors.Accent.copy(alpha = 0.85f)
                        ClipKind.IMAGE -> ClearCutColors.Teal.copy(alpha = 0.8f)
                        ClipKind.TEXT -> ClearCutColors.Structural.copy(alpha = 0.9f)
                        ClipKind.PICTURE_OVERLAY -> ClearCutColors.RepeatedVisual.copy(alpha = 0.8f)
                        ClipKind.AUDIO -> ClearCutColors.RepeatedAudio.copy(alpha = 0.8f)
                        ClipKind.VOICEOVER -> ClearCutColors.SimilarVisual.copy(alpha = 0.8f)
                    }
                    Box(
                        Modifier
                            .offset(x = widthDp * startFraction)
                            .fillMaxWidth(widthFraction)
                            .height(42.dp)
                            .align(Alignment.CenterStart)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color)
                            .clickable { onSelectClip(if (clip.id == selectedClipId) null else clip.id) }
                            .then(
                                if (clip.id == selectedClipId) {
                                    Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                                } else Modifier
                            )
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            clip.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (track.clips.isEmpty()) {
                    Text(
                        if (track.locked) "Locked — unlock to edit" else "Empty — tap + to add media",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClearCutColors.OnInkFaint,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
            }
            IconButton(onClick = { if (!track.locked) onAdd() }, enabled = !track.locked, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Add to ${track.name}", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onToggleMute, enabled = !track.locked, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (track.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = "Mute ${track.name}",
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onToggleLock, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (track.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (track.locked) "Unlock ${track.name}" else "Lock ${track.name}",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

