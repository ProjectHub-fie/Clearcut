package com.projecthub.clearcut.feature.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projecthub.clearcut.core.designsystem.ClearCutColors
import com.projecthub.clearcut.core.designsystem.EmptyState
import com.projecthub.clearcut.core.designsystem.PremiumCard
import com.projecthub.clearcut.core.storage.ProjectEntity

class ProjectsViewModel(private val container: com.projecthub.clearcut.di.AppContainer) : ViewModel() {
    val projects = container.projectDao.observeAll()
    fun delete(id: String) {
        viewModelScope.launch { runCatching { container.projectDao.deleteById(id) } }
    }

    companion object {
        val Factory = com.projecthub.clearcut.di.viewModelFactory { _, c -> ProjectsViewModel(c) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsRoute(
    onOpenProject: (String) -> Unit,
    vm: ProjectsViewModel = viewModel(factory = ProjectsViewModel.Factory),
) {
    val projects by vm.projects.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (projects.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                EmptyState(
                    icon = "🗂",
                    title = "No projects yet",
                    message = "Import media and it will appear here so you can re-scan or continue editing.",
                    modifier = Modifier.padding(bottom = 40.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(project, onOpen = { onOpenProject(project.sourceUri) }, onDelete = { vm.delete(project.id) })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: ProjectEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    PremiumCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).background(
                    Brush.linearGradient(listOf(ClearCutColors.AccentMuted, ClearCutColors.Teal.copy(alpha = 0.5f))),
                    RoundedCornerShape(16.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Movie, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${project.durationUs / 1000 / 60}m · ${if (project.hasVideo) "video" else "audio"} · status ${project.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClearCutColors.OnInkDim,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete project", tint = ClearCutColors.Error)
            }
        }
    }
}