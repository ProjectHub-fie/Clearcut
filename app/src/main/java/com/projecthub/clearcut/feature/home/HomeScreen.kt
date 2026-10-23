package com.projecthub.clearcut.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projecthub.clearcut.core.designsystem.ClearCutColors
import com.projecthub.clearcut.core.designsystem.PremiumCard
import com.projecthub.clearcut.core.designsystem.SectionHeader
import com.projecthub.clearcut.core.storage.ProjectEntity

@Composable
fun HomeRoute(
    onNavigateToImport: () -> Unit,
    onOpenScanner: (String) -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val ui by vm.uiState.collectAsState()
    val projects by vm.projects.collectAsState()
    val scanHistory by vm.scanHistory.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { HomeHero(projects.size) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionCard(
                    title = "Import from device",
                    subtitle = "Pick a file from storage",
                    icon = Icons.Filled.Add,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToImport,
                )
                ActionCard(
                    title = "Import from URL",
                    subtitle = "Direct media link",
                    icon = Icons.Filled.Download,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToImport,
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionCard(
                    title = "Start new scan",
                    subtitle = "Full-duration analysis",
                    icon = Icons.Filled.Search,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToImport,
                )
                ActionCard(
                    title = "Copyright Lab",
                    subtitle = "EDL editing tools",
                    icon = Icons.Filled.Movie,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToImport,
                )
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Recent projects", "Continue where you left off")
            Spacer(Modifier.height(8.dp))
        }
        if (projects.isEmpty()) {
            item {
                Text(
                    "No projects yet. Import media to begin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClearCutColors.OnInkDim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        } else {
            items(projects.take(5), key = { it.id }) { project ->
                ProjectRow(project, onClick = { onOpenScanner(project.sourceUri) })
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
            SectionHeader("Scan history", "Recent full-duration analyses")
            Spacer(Modifier.height(8.dp))
        }
        if (scanHistory.isEmpty()) {
            item {
                Text(
                    "No scans yet. Analyses are stored locally.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClearCutColors.OnInkDim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        } else {
            items(scanHistory.take(4)) { scan ->
                ScanHistoryRow(name = scan.mediaName, findingCount = scan.findingCount, date = scan.scannedAtEpochMs, coverage = scan.coverageFraction)
            }
        }

        item {
            StorageCard(ui)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HomeHero(projectCount: Int) {
    val dark = isSystemInDarkTheme()
    val bgTop = if (dark) ClearCutColors.InkElevated else Color(0xFFEDEDF2)
    val bgBottom = if (dark) Color(0xFF0C0E11) else Color(0xFFF7F7F5)
    val onBg = if (dark) ClearCutColors.OnInk else Color(0xFF1B1D22)
    val onBgDim = if (dark) ClearCutColors.OnInkDim else Color(0xFF52565E)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Column {
            Text("ClearCut", style = MaterialTheme.typography.displaySmall, color = onBg)
            Spacer(Modifier.height(6.dp))
            Text(
                "Database-free media analysis and editing.\nRepeated sections, similarities, and editing tools — all on-device.",
                style = MaterialTheme.typography.bodyMedium,
                color = onBgDim,
            )
            if (projectCount > 0) {
                Spacer(Modifier.height(10.dp))
                Text("$projectCount saved project(s)", style = MaterialTheme.typography.labelMedium, color = ClearCutColors.Accent)
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    PremiumCard(modifier = modifier.clickable { onClick() }) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(38.dp).background(ClearCutColors.Accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = ClearCutColors.Accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ClearCutColors.OnInkDim)
        }
    }
}

@Composable
private fun ProjectRow(project: ProjectEntity, onClick: () -> Unit) {
    PremiumCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).clickable { onClick() }
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(
                    Brush.linearGradient(listOf(ClearCutColors.AccentMuted, ClearCutColors.Teal.copy(alpha = 0.5f))),
                    RoundedCornerShape(14.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Movie, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${project.durationUs / 1000 / 60}m · ${if (project.hasVideo) "video" else "audio"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClearCutColors.OnInkDim,
                )
            }
            Icon(Icons.Outlined.MoreHoriz, contentDescription = "More", tint = ClearCutColors.OnInkFaint)
        }
    }
}

@Composable
private fun ScanHistoryRow(name: String, findingCount: Int, date: Long, coverage: Float) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
        Text("${(coverage * 100).toInt()}% covered", style = MaterialTheme.typography.labelSmall, color = ClearCutColors.OnInkDim)
        Spacer(Modifier.width(8.dp))
        Text(
            "$findingCount finding(s)",
            style = MaterialTheme.typography.labelMedium,
            color = if (findingCount > 0) ClearCutColors.Warning else ClearCutColors.Success,
        )
    }
}

@Composable
private fun StorageCard(ui: HomeUiState) {
    val usage = ui.storageUsage
    if (usage == null) return
    PremiumCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Storage", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${usage.freeBytes / 1024 / 1024} MB free of ${usage.totalBytes / 1024 / 1024} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClearCutColors.OnInkDim,
                )
            }
            CircularProgressIndicator(
                progress = { usage.usedBytes.toFloat() / usage.totalBytes.toFloat() },
                modifier = Modifier.size(48.dp),
                color = ClearCutColors.Accent,
                trackColor = ClearCutColors.Accent.copy(alpha = 0.14f),
            )
        }
    }
}