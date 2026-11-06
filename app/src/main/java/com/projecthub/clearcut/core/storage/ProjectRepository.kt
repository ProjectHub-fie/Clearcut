package com.projecthub.clearcut.core.storage

import com.projecthub.clearcut.core.model.ProjectTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Project store mixing in-memory state (so the editor never blocks) with
 * best-effort Room persistence for process-death recovery. The Room column
 * carries a full [ProjectTimeline] JSON snapshot.
 */
class ProjectRepository(
    private val dao: ProjectDao,
) {
    private val _projects = MutableStateFlow<Map<String, ProjectTimeline>>(emptyMap())
    val projects: StateFlow<Map<String, ProjectTimeline>> = _projects.asStateFlow()

    fun getProject(id: String): ProjectTimeline? = _projects.value[id]

    fun getAllProjects(): List<ProjectTimeline> =
        _projects.value.values.sortedByDescending { it.updatedAtEpochMs }

    /** Load persisted project snapshots into memory (called once at startup). */
    suspend fun loadPersisted(): List<ProjectTimeline> {
        val loaded = runCatching {
            dao.getAll()
        }.getOrNull().orEmpty().mapNotNull { TimelineCodec.decodeNullable(it.edlJson) }
        _projects.value = loaded.associateBy { it.projectId }
        return loaded
    }

    suspend fun save(project: ProjectTimeline) {
        _projects.value = _projects.value + (project.projectId to project)
        runCatching {
            dao.upsert(
                ProjectEntity(
                    id = project.projectId,
                    name = project.name,
                    sourceUri = project.sourceUri ?: "",
                    sourceName = project.name.ifBlank { "project" },
                    durationUs = project.durationUs,
                    hasAudio = project.hasAudioClips,
                    hasVideo = project.mainTrack?.clips?.any { it.hasVisual } == true,
                    createdAtEpochMs = project.createdAtEpochMs,
                    updatedAtEpochMs = project.updatedAtEpochMs,
                    edlJson = TimelineCodec.encode(project),
                )
            )
        }
    }

    suspend fun delete(id: String) {
        _projects.value = _projects.value - id
        runCatching { dao.deleteById(id) }
    }
}

/** Serializes a [ProjectTimeline] into/out of the Room column. */
object TimelineCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(timeline: ProjectTimeline): String = json.encodeToString(ProjectTimeline.serializer(), timeline)

    fun decodeNullable(raw: String?): ProjectTimeline? =
        raw?.let { runCatching { json.decodeFromString(ProjectTimeline.serializer(), it) }.getOrNull() }
}