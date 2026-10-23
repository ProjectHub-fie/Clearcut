package com.projecthub.clearcut.feature.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.projecthub.clearcut.core.media.EdlEvaluator
import com.projecthub.clearcut.core.model.AnalysisTrack
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.Edl
import com.projecthub.clearcut.core.model.EdlOperation
import com.projecthub.clearcut.core.model.EditOperationType
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.OperationState
import com.projecthub.clearcut.core.model.SyncPolicy
import com.projecthub.clearcut.core.model.TimeRange
import com.projecthub.clearcut.core.model.ValidationStatus
import com.projecthub.clearcut.di.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class EditorUiState(
    val edl: Edl? = null,
    val selectedOperationId: String? = null,
    val outputDurationUs: Long = 0,
    val validationWarnings: List<String> = emptyList(),
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as com.projecthub.clearcut.app.ClearCutApplication).container

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _media = MutableStateFlow<MediaInfo?>(null)
    val media: StateFlow<MediaInfo?> = _media.asStateFlow()

    private val _previewRange = MutableStateFlow<TimeRange?>(null)
    val previewRange: StateFlow<TimeRange?> = _previewRange.asStateFlow()

    fun setMedia(media: MediaInfo) {
        _media.value = media
        _uiState.value = EditorUiState(
            edl = Edl(mediaId = media.uri, sourceDurationUs = media.durationUs),
            selectedOperationId = null,
            outputDurationUs = media.durationUs,
        )
    }

    fun restore(edl: Edl, media: MediaInfo) {
        _media.value = media
        refresh(edl)
        _previewRange.value = null
    }

    private fun refresh(edl: Edl) {
        val expected = EdlEvaluator.expectedDuration(edl)
        val warnings = mutableListOf<String>()
        if (EdlEvaluator.hasOverlappingCuts(edl)) warnings.add("Overlapping cut ranges. Only non-overlapping cuts render cleanly.")
        val statuses = EdlEvaluator.validate(edl)
        if (statuses.any { it == ValidationStatus.INVALID }) warnings.add("Some operations are out of range and will be rejected at render time.")
        if (expected <= 0) warnings.add("The current edits produce an empty output.")
        _uiState.value = EditorUiState(
            edl = edl,
            selectedOperationId = _uiState.value.selectedOperationId,
            outputDurationUs = expected,
            validationWarnings = warnings,
        )
    }

    val currentEdl: Edl? get() = _uiState.value.edl

    /** Add a cut operation removing [range] from the given track. */
    fun cutRange(range: TimeRange, track: AnalysisTrack, syncPolicy: SyncPolicy) {
        val edl = _uiState.value.edl ?: return
        if (range.durationUs <= 0) return
        // Prevent duplicates: if an identical cut already exists, keep it simple.
        if (edl.operations.any { it.operationType == EditOperationType.CUT && it.sourceRange == range && it.track == track }) {
            return
        }
        val op = EdlOperation(
            id = UUID.randomUUID().toString().take(8),
            sourceRange = range,
            track = track,
            operationType = EditOperationType.CUT,
            syncPolicy = syncPolicy,
            crossfadeMs = 120,
            validationStatus = if (range.endUs <= edl.sourceDurationUs) ValidationStatus.VALID else ValidationStatus.INVALID,
        )
        refresh(edl.plus(op))
    }

    fun replaceRange(range: TimeRange, track: AnalysisTrack, replacementUri: String, syncPolicy: SyncPolicy) {
        val edl = _uiState.value.edl ?: return
        val op = EdlOperation(
            id = UUID.randomUUID().toString().take(8),
            sourceRange = range,
            track = track,
            operationType = EditOperationType.REPLACE,
            replacementSourceUri = replacementUri,
            syncPolicy = syncPolicy,
            crossfadeMs = 150,
            validationStatus = ValidationStatus.VALID,
        )
        refresh(edl.plus(op))
    }

    /** Add a crossfade boundary to the operation with [operationId]. */
    fun setCrossfade(operationId: String, ms: Int) {
        val edl = _uiState.value.edl ?: return
        val updated = edl.operations.map {
            if (it.id == operationId) it.copy(crossfadeMs = ms) else it
        }
        refresh(edl.copy(operations = updated))
    }

    fun setSyncPolicy(operationId: String, policy: SyncPolicy) {
        val edl = _uiState.value.edl ?: return
        val updated = edl.operations.map {
            if (it.id == operationId) it.copy(syncPolicy = policy) else it
        }
        refresh(edl.copy(operations = updated))
    }

    fun removeOperation(operationId: String) {
        val edl = _uiState.value.edl ?: return
        refresh(edl.copy(operations = edl.operations.filterNot { it.id == operationId }))
    }

    fun selectOperation(operationId: String?) {
        _uiState.value = _uiState.value.copy(selectedOperationId = operationId)
    }

    /** Undo = pop last added operation. */
    fun undo() {
        val edl = _uiState.value.edl ?: return
        if (edl.operations.isEmpty()) return
        refresh(edl.copy(operations = edl.operations.dropLast(1)))
        _previewRange.value = null
    }

    fun clearAll() {
        val media = _media.value ?: return
        _uiState.value = EditorUiState(
            edl = Edl(mediaId = media.uri, sourceDurationUs = media.durationUs),
            outputDurationUs = media.durationUs,
        )
    }

    fun setPreviewRange(range: TimeRange?) { _previewRange.value = range }

    fun openReplacementPicker() {}

    companion object {
        val Factory = viewModelFactory { app, _ -> EditorViewModel(app) }
    }
}