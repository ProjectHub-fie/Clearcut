package com.projecthub.clearcut.feature.timeline

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.projecthub.clearcut.core.media.VoiceoverRecorder
import com.projecthub.clearcut.core.model.BlendMode
import com.projecthub.clearcut.core.model.ClipKind
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.ExportPreset
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.OperationState
import com.projecthub.clearcut.core.model.ProjectTimeline
import com.projecthub.clearcut.core.model.TextPreset
import com.projecthub.clearcut.core.model.TimelineClip
import com.projecthub.clearcut.core.model.TrackKind
import com.projecthub.clearcut.core.model.TransitionKind
import com.projecthub.clearcut.di.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class TimelineUiState(
    val timeline: ProjectTimeline? = null,
    val selectedClipId: String? = null,
    val playheadUs: Long = 0L,
    val isPlaying: Boolean = false,
    val exportState: OperationState<String> = OperationState.Idle,
    val recording: Boolean = false,
    val rendererWarnings: List<String> = emptyList(),
    val renamed: String = "",
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TimelineViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as com.projecthub.clearcut.app.ClearCutApplication).container
    private val repository = container.projectRepository
    private val renderer = container.timelineRenderer

    private val recorder = VoiceoverRecorder(app)

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    val voiceoverState = recorder.state

    /** Next media kind to pick (set by toolbar chips, consumed by the screen). */
    @Volatile
    private var _pendingPick: ClipKind? = null
    fun consumePendingPick(): ClipKind? = _pendingPick.also { _pendingPick = null }

    private var media: MediaInfo? = null

    companion object {
        val Factory = viewModelFactory { app, _ -> TimelineViewModel(app) }
    }

    // ---- Project loading -----------------------------------------------------------------------

    fun open(media: MediaInfo) {
        this.media = media
        val existing = repository.getProject(media.uri) ?: newProject(media)
        _uiState.value = TimelineUiState(
            timeline = existing,
            renamed = existing.name,
            rendererWarnings = renderer.validateTimeline(existing),
        )
    }

    fun openProject(timeline: ProjectTimeline) {
        this.media = null
        _uiState.value = TimelineUiState(
            timeline = timeline,
            renamed = timeline.name,
            rendererWarnings = renderer.validateTimeline(timeline),
        )
    }

    private fun newProject(media: MediaInfo): ProjectTimeline {
        val duration = media.durationUs.takeIf { it > 0 } ?: 60_000_000L
        val tracks = ProjectTimeline.defaultTracks()
        val main = tracks.first { it.kind == TrackKind.VIDEO }
        val clip = TimelineClip(
            id = "clip-${UUID.randomUUID().toString().take(6)}",
            kind = if (media.isAudioOnly) ClipKind.AUDIO else ClipKind.VIDEO,
            trackId = main.id,
            sourceUri = media.uri,
            sourceStartUs = 0L,
            sourceEndUs = duration,
            startUs = 0L,
            durationUs = duration,
            displayName = media.displayName,
        )
        return ProjectTimeline(
            projectId = media.uri,
            name = media.displayName,
            sourceUri = media.uri,
            sourceDurationUs = duration,
            tracks = tracks.map { if (it.id == main.id) main.copy(clips = listOf(clip)) else it },
        )
    }

    fun rename(name: String) {
        _uiState.value = _uiState.value.copy(renamed = name)
    }

    fun persist() {
        val t = _uiState.value.timeline ?: return
        viewModelScope.launch {
            repository.save(t.copy(name = _uiState.value.renamed.ifBlank { t.name }))
        }
    }

    // ---- Timeline mutations -------------------------------------------------------------------

    private fun update(transform: (ProjectTimeline) -> ProjectTimeline) {
        val t = _uiState.value.timeline ?: return
        val newT = transform(t)
        _uiState.value = _uiState.value.copy(
            timeline = newT,
            rendererWarnings = renderer.validateTimeline(newT),
        )
        persist()
    }

    fun addMediaClip(uri: Uri, displayName: String, kind: ClipKind, trackKind: TrackKind) {
        val t = _uiState.value.timeline ?: return
        val track = t.tracks.firstOrNull { it.kind == trackKind }
            ?: t.tracks.firstOrNull { it.kind == TrackKind.OVERLAY }
            ?: return
        val clip = TimelineClip(
            id = "clip-${UUID.randomUUID().toString().take(6)}",
            kind = kind,
            trackId = track.id,
            sourceUri = uri.toString(),
            sourceStartUs = 0L,
            sourceEndUs = 120_000_000L,
            startUs = t.durationUs,
            durationUs = 5_000_000L,
            displayName = displayName,
        )
        update { it.upsertClip(clip) }
        select(clip.id)
    }

    /** Kept for the toolbar "Add media" chips — the picker is wired in the screen. */
    fun addMediaClipPicker(kind: ClipKind) {
        // The screen observes this and launches the relevant document picker.
        _pendingPick = kind
    }

    /** Nudge a clip left/right on its lane by a delta (in microseconds). */
    fun moveClipBy(clipId: String, deltaUs: Long) {
        val t = _uiState.value.timeline ?: return
        val clip = t.clip(clipId) ?: return
        update { it.moveClip(clipId, (clip.startUs + deltaUs).coerceAtLeast(0L)) }
    }

    /** Split the selected clip at the playhead (if inside the clip). */
    fun splitAtPlayhead() {
        val t = _uiState.value.timeline ?: return
        val clipId = _uiState.value.selectedClipId ?: t.clipAt(_uiState.value.playheadUs)?.id ?: return
        val clip = t.clip(clipId) ?: return
        val playhead = _uiState.value.playheadUs
        if (playhead <= clip.startUs || playhead >= clip.endUs) return
        val first = clip.copy(
            id = "clip-${UUID.randomUUID().toString().take(6)}",
            durationUs = playhead - clip.startUs,
            sourceEndUs = clip.sourceStartUs + (playhead - clip.startUs),
            displayName = clip.displayName,
        )
        val second = clip.copy(
            id = "clip-${UUID.randomUUID().toString().take(6)}",
            startUs = playhead,
            durationUs = clip.endUs - playhead,
            sourceStartUs = clip.sourceStartUs + (playhead - clip.startUs),
        )
        update { it.removeClip(clip.id).upsertClip(first).upsertClip(second) }
        select(first.id)
    }

    fun setAudioEffects(clipId: String, effects: List<com.projecthub.clearcut.core.model.AudioEffectPreset>) {
        updateClip(clipId) { it.copy(audioEffects = effects) }
    }

    fun addTextClip() {
        val t = _uiState.value.timeline ?: return
        val track = t.tracks.firstOrNull { it.kind == TrackKind.OVERLAY }
            ?: t.tracks.firstOrNull { it.kind == TrackKind.TEXT }
            ?: return
        val clip = TimelineClip(
            id = "clip-${UUID.randomUUID().toString().take(6)}",
            kind = ClipKind.TEXT,
            trackId = track.id,
            startUs = 0L,
            durationUs = 3_000_000L,
            displayName = "Text",
            text = "Your text",
            fontSizeSp = 36f,
            textPreset = TextPreset.TITLE,
        )
        update { it.upsertClip(clip) }
        select(clip.id)
    }

    fun movePlayhead(us: Long) {
        _uiState.value = _uiState.value.copy(playheadUs = us.coerceAtLeast(0L))
    }

    fun play() { _uiState.value = _uiState.value.copy(isPlaying = true) }
    fun pause() { _uiState.value = _uiState.value.copy(isPlaying = false) }

    fun select(clipId: String?) {
        _uiState.value = _uiState.value.copy(selectedClipId = clipId)
    }

    fun deleteClip(clipId: String) {
        if (_uiState.value.selectedClipId == clipId) select(null)
        update { it.removeClip(clipId) }
    }

    fun duplicateClip(clipId: String) {
        val t = _uiState.value.timeline ?: return
        val clip = t.clip(clipId) ?: return
        val dup = clip.copy(
            id = "clip-${UUID.randomUUID().toString().take(6)}",
            startUs = clip.endUs,
            displayName = "${clip.displayName} copy",
        )
        update { it.upsertClip(dup) }
    }

    fun toggleTrackMute(trackId: String) = update { it.toggleTrackMute(trackId) }
    fun toggleTrackLock(trackId: String) = update { it.toggleTrackLock(trackId) }

    // ---- Per-clip helpers ----------------------------------------------------------------------

    fun setSpeed(clipId: String, speed: Float) = updateClip(clipId) { it.copy(speed = it.speed.copy(speed = speed.coerceIn(0.1f, 4f))) }
    fun setPreservePitch(clipId: String, preserve: Boolean) = updateClip(clipId) { it.copy(speed = it.speed.copy(pitchPreserved = preserve)) }
    fun setBrightness(clipId: String, v: Float) = updateClip(clipId) { it.copy(colorGrade = it.colorGrade.copy(brightness = v.coerceIn(-1f, 1f))) }
    fun setContrast(clipId: String, v: Float) = updateClip(clipId) { it.copy(colorGrade = it.colorGrade.copy(contrast = v.coerceIn(-1f, 1f))) }
    fun setSaturation(clipId: String, v: Float) = updateClip(clipId) { it.copy(colorGrade = it.colorGrade.copy(saturation = v.coerceIn(-100f, 100f))) }
    fun setGrayscale(clipId: String, on: Boolean) = updateClip(clipId) { it.copy(grayscale = on) }
    fun setInvert(clipId: String, on: Boolean) = updateClip(clipId) { it.copy(invert = on) }
    fun setOpacity(clipId: String, v: Float) = updateClip(clipId) { it.copy(opacity = v.coerceIn(0f, 1f)) }
    fun setBlur(clipId: String, v: Float) = updateClip(clipId) { it.copy(blurRadius = v.coerceIn(0f, 40f)) }
    fun setVolume(clipId: String, v: Float) = updateClip(clipId) { it.copy(volumeEnvelope = it.volumeEnvelope.copy(baseGain = v.coerceIn(0f, 2f))) }
    fun setFadeIn(clipId: String, ms: Int) = updateClip(clipId) { it.copy(fadeInUs = ms * 1000L) }
    fun setFadeOut(clipId: String, ms: Int) = updateClip(clipId) { it.copy(fadeOutUs = ms * 1000L) }
    fun setPiPScale(clipId: String, scale: Float) = updateClip(clipId) { it.copy(transform = it.transform.copy(scaleX = scale.coerceIn(0.05f, 1.5f), scaleY = scale.coerceIn(0.05f, 1.5f))) }
    fun setPiPRotation(clipId: String, degrees: Float) = updateClip(clipId) { it.copy(transform = it.transform.copy(rotationDegrees = degrees)) }
    fun setBlendMode(clipId: String, mode: BlendMode) = updateClip(clipId) { it.copy(blendMode = mode) }
    fun setText(clipId: String, text: String) = updateClip(clipId) { it.copy(text = text) }
    fun setFontSize(clipId: String, sp: Float) = updateClip(clipId) { it.copy(fontSizeSp = sp) }
    fun setTextColor(clipId: String, color: Long) = updateClip(clipId) { it.copy(textColor = color) }
    fun setTextPreset(clipId: String, preset: TextPreset) = updateClip(clipId) { it.copy(textPreset = preset) }
    fun setTransition(clipId: String, transition: TransitionKind) = updateClip(clipId) { it.copy(transitionOut = transition) }

    fun setExportPreset(preset: ExportPreset) = update { it.copy(exportPreset = preset) }

    // ---- Voiceover -----------------------------------------------------------------------------

    fun startRecording() {
        if (recorder.start()) _uiState.value = _uiState.value.copy(recording = true)
    }

    fun tickRecording() = recorder.tick()

    fun stopRecording() {
        val t = _uiState.value.timeline ?: return
        val result = recorder.stop()
        _uiState.value = _uiState.value.copy(recording = false)
        val finished = result as? VoiceoverRecorder.State.Finished ?: return
        if (!finished.file.exists()) return
        val track = t.tracks.firstOrNull { it.kind == TrackKind.VOICEOVER } ?: return
        val clip = TimelineClip(
            id = "clip-${UUID.randomUUID().toString().take(6)}",
            kind = ClipKind.VOICEOVER,
            trackId = track.id,
            sourceUri = Uri.fromFile(finished.file).toString(),
            sourceStartUs = 0L,
            sourceEndUs = finished.durationMs * 1000L,
            startUs = t.durationUs,
            durationUs = finished.durationMs * 1000L,
            displayName = "Voiceover ${finished.durationMs / 1000}s",
        )
        update { it.upsertClip(clip) }
        select(clip.id)
    }

    fun cancelRecording() {
        recorder.cancel()
        _uiState.value = _uiState.value.copy(recording = false)
    }

    // ---- Export --------------------------------------------------------------------------------

    fun export() {
        val t = _uiState.value.timeline ?: return
        if (_uiState.value.exportState.isActive) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(exportState = OperationState.Validating("Building composition…"))
            try {
                val safeName = t.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project" }
                val moviesDir = getApplication<Application>().getExternalFilesDir(
                    android.os.Environment.DIRECTORY_MOVIES
                ) ?: getApplication<Application>().cacheDir
                moviesDir.mkdirs()
                val outputFile = File(moviesDir, "ClearCut_${safeName}_${System.currentTimeMillis()}.mp4")
                val result = renderer.render(t, outputFile.absolutePath) { progress ->
                    _uiState.value = _uiState.value.copy(
                        exportState = OperationState.InProgress("Rendering…", progress),
                    )
                }
                _uiState.value = _uiState.value.copy(
                    exportState = if (result.warnings.isEmpty()) OperationState.Completed(result.outputPath)
                    else OperationState.CompletedWithWarnings(result.outputPath, result.warnings),
                )
            } catch (e: ClearCutError) {
                _uiState.value = _uiState.value.copy(exportState = OperationState.Failed(e))
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(exportState = OperationState.Failed(ClearCutError.fromThrowable(e, "export")))
            }
        }
    }

    fun cancelExport() {
        _uiState.value = _uiState.value.copy(exportState = OperationState.Cancelled("export"))
    }

    fun resetExport() {
        _uiState.value = _uiState.value.copy(exportState = OperationState.Idle)
    }

    private fun updateClip(clipId: String, transform: (TimelineClip) -> TimelineClip) {
        update { t ->
            val clip = t.clip(clipId) ?: return@update t
            t.upsertClip(transform(clip))
        }
    }
}