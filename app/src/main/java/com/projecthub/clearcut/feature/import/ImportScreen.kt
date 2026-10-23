package com.projecthub.clearcut.feature.import

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import com.projecthub.clearcut.core.designsystem.ClearCutColors
import com.projecthub.clearcut.core.designsystem.ProcessingStateView
import com.projecthub.clearcut.core.designsystem.PremiumCard
import com.projecthub.clearcut.core.model.OperationState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportRoute(
    onBack: () -> Unit,
    onImported: (String) -> Unit,
    onOpenEditor: (String) -> Unit,
    vm: ImportViewModel = viewModel(factory = ImportViewModel.Factory),
) {
    val state by vm.importState.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    val urlDraft by vm.urlDraft.collectAsState()
    val urlInfo by vm.urlInfo.collectAsState()
    val recovery by vm.recoveryAction.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importLocal(it) }
    }
    val pickerAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importLocal(it) }
    }
    val pickerReplacement = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importLocal(it) }
    }

    LaunchedEffect(state) {
        val s = state
        if (s is OperationState.Completed && s.value != null) {
            onImported(s.value.media.uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import media") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text("Import from device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            PremiumCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = ClearCutColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pick a file from storage", style = MaterialTheme.typography.titleSmall)
                            Text("MP4, MKV, MOV, MP3, AAC, M4A, WAV, OGG", style = MaterialTheme.typography.bodySmall, color = ClearCutColors.OnInkDim)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open media picker")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Files are analyzed on-device and never uploaded.",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClearCutColors.OnInkDim,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = ClearCutColors.OutlineDark.copy(alpha = 0.5f))
            Spacer(Modifier.height(24.dp))

            Text("Import from URL", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            PremiumCard {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = vm::setUrlDraft,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Direct media URL (https://…)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Supported: direct http(s) links to audio/video files. " +
                            "YouTube and Facebook page URLs are not downloadable and are rejected safely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClearCutColors.OnInkDim,
                    )
                    Spacer(Modifier.height(10.dp))
                    urlInfo?.let { info ->
                        Text("Source: ${info.sourceLabel}", style = MaterialTheme.typography.labelMedium, color = ClearCutColors.Teal)
                        if (info.sizeLabel != null) {
                            Text("Estimated size: ${info.sizeLabel}", style = MaterialTheme.typography.labelSmall, color = ClearCutColors.OnInkDim)
                        }
                        Text("Destination: Downloads area (private)", style = MaterialTheme.typography.labelSmall, color = ClearCutColors.OnInkDim)
                        Spacer(Modifier.height(6.dp))
                    }
                    Row {
                        OutlinedButton(
                            onClick = { if (urlDraft.isNotBlank()) vm.preflightUrl(urlDraft) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Check URL")
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { vm.importUrl() },
                            modifier = Modifier.weight(1f),
                            enabled = urlDraft.isNotBlank() && !state.isActive,
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Progress / results area
            when (val s = state) {
                is OperationState.InProgress -> {
                    PremiumCard {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.phase, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(10.dp))
                            val p = progress?.progress
                            if (p != null) {
                                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "${(p * 100).toInt()}% · ${formatMb(progress?.bytesDownloaded)} of ${progress?.totalBytes?.let { formatMb(it) } ?: "unknown"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClearCutColors.OnInkDim,
                                )
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = ClearCutColors.Accent)
                            }
                            Spacer(Modifier.height(6.dp))
                            val done = progress?.let { it.bytesDownloaded > 0 && it.totalBytes != null && it.bytesDownloaded >= it.totalBytes }
                            Button(onClick = { onBack() }, enabled = done == true) { Text("Finish (imported)") }
                        }
                    }
                }
                is OperationState.Failed -> {
                    ProcessingStateView(
                        state = s,
                        onRetry = { vm.importUrl() },
                        onDismissError = { vm.clearState() },
                    )
                    recovery?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = ClearCutColors.Warning)
                    }
                }
                is OperationState.Completed -> Unit
                else -> Unit
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = ClearCutColors.OutlineDark.copy(alpha = 0.5f))
            Spacer(Modifier.height(16.dp))
            Text("Other options", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "No social-account passwords are ever requested. Media is never uploaded automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = ClearCutColors.OnInkDim,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatMb(bytes: Long?): String {
    if (bytes == null) return "0 B"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb) else "${bytes / 1024} KB"
}