package com.projecthub.clearcut.feature.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projecthub.clearcut.core.designsystem.ClearCutColors
import com.projecthub.clearcut.core.designsystem.ProcessingStateView
import com.projecthub.clearcut.core.designsystem.PremiumCard
import com.projecthub.clearcut.core.media.BroadcastNotifier
import com.projecthub.clearcut.core.media.ExportEvent
import com.projecthub.clearcut.core.model.OperationState
import com.projecthub.clearcut.core.model.TimeRange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportRoute(
    onBack: () -> Unit,
    vm: ExportViewModel = viewModel(factory = ExportViewModel.Factory),
) {
    val state by vm.exportState.collectAsState()

    DisposableEffect(Unit) {
        val listener: (ExportEvent) -> Unit = { event ->
            when (event) {
                is ExportEvent.Success -> vm.onRenderCompleted(event.path)
                is ExportEvent.Error -> vm.onRenderError(event.error)
            }
        }
        BroadcastNotifier.addListener(listener)
        onDispose { BroadcastNotifier.removeListener(listener) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            when (val s = state) {
                is OperationState.Completed<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val data = s.value as ExportResultData
                    PremiumCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ClearCutColors.Success, modifier = Modifier.padding(bottom = 8.dp))
                            Text("Export complete", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(data.path, style = MaterialTheme.typography.bodySmall, color = ClearCutColors.OnInkDim)
                            Text("Duration: ${TimeRange.formatUs(data.durationUs)} · ${data.sizeBytes / 1024 / 1024} MB", style = MaterialTheme.typography.bodySmall, color = ClearCutColors.OnInkDim)
                            if (!data.validated) {
                                Spacer(Modifier.height(8.dp))
                                Text("Validation reported warnings:", style = MaterialTheme.typography.labelMedium, color = ClearCutColors.Warning)
                                data.validationWarnings.forEach {
                                    Text("- $it", style = MaterialTheme.typography.labelSmall, color = ClearCutColors.Warning)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row {
                                Button(onClick = { onBack() }) { Text("Done") }
                            }
                        }
                    }
                }
                is OperationState.CompletedWithWarnings<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val data = s.value as ExportResultData
                    PremiumCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Export finished with warnings", style = MaterialTheme.typography.titleMedium, color = ClearCutColors.Warning)
                            Spacer(Modifier.height(6.dp))
                            data.validationWarnings.forEach {
                                Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = ClearCutColors.Warning)
                            }
                            Text(data.path, style = MaterialTheme.typography.bodySmall, color = ClearCutColors.OnInkDim)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { onBack() }) { Text("Done") }
                        }
                    }
                }
                is OperationState.Failed -> {
                    ProcessingStateView(
                        state = s,
                        onRetry = { vm.retry() },
                        onDismissError = { onBack() },
                    )
                }
                is OperationState.Cancelled -> {
                    Text("Export cancelled.", style = MaterialTheme.typography.bodyMedium, color = ClearCutColors.Warning)
                }
                is OperationState.InProgress -> {
                    ProcessingStateView(state = s, onCancel = { vm.cancelExport() })
                }
                else -> {
                    Text(
                        "Exports render MP4 (H.264/AAC) on-device. The preview and export use the same edit decision list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClearCutColors.OnInkDim,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}