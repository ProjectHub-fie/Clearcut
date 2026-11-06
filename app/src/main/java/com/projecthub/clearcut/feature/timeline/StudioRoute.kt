package com.projecthub.clearcut.feature.timeline

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projecthub.clearcut.core.designsystem.ClearCutColors
import com.projecthub.clearcut.core.designsystem.EmptyState
import com.projecthub.clearcut.core.designsystem.PremiumCard
import com.projecthub.clearcut.core.model.ProjectTimeline
import com.projecthub.clearcut.di.viewModelFactory
import kotlinx.coroutines.launch

class StudioViewModel(app: Application) : ViewModel() {
    private val container = (app as com.projecthub.clearcut.app.ClearCutApplication).container
    private val repository = container.projectRepository

    private val _projects = androidx.compose.runtime.mutableStateOf<List<ProjectTimeline>>(emptyList())
    val projects: androidx.compose.runtime.State<List<ProjectTimeline>> = _projects

    init {
        viewModelScope.launch {
            repository.loadPersisted()
            _projects.value = repository.getAllProjects()
        }
    }

    companion object {
        val Factory = viewModelFactory { app, _ -> StudioViewModel(app) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioRoute(
    onOpenProject: (ProjectTimeline) -> Unit,
    onBack: () -> Unit,
    onNewProject: () -> Unit,
    vm: StudioViewModel = viewModel(factory = StudioViewModel.Factory),
) {
    val projects = vm.projects.value
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Studio") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Button(
                onClick = onNewProject,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("New project")
            }
            if (projects.isEmpty()) {
                EmptyState(
                    icon = "🎥",
                    title = "No projects yet",
                    message = "Pick a video or image to start editing on the timeline.",
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                ) {
                    items(projects, key = { it.projectId }) { project ->
                        ProjectRow(project) { onOpenProject(project) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(project: ProjectTimeline, onClick: () -> Unit) {
    PremiumCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                tint = ClearCutColors.Accent,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${project.tracks.size} tracks · ${com.projecthub.clearcut.core.model.TimeRange.formatUs(project.durationUs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClearCutColors.OnInkDim,
                )
            }
        }
    }
}