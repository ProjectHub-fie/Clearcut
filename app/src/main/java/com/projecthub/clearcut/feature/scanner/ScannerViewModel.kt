package com.projecthub.clearcut.feature.scanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.projecthub.clearcut.core.model.AnalysisMethod
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.Finding
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.OperationState
import com.projecthub.clearcut.core.model.SamplingIntensity
import com.projecthub.clearcut.core.model.ScanReport
import com.projecthub.clearcut.core.model.ScanSettingsSnapshot
import com.projecthub.clearcut.core.model.TimeRange
import com.projecthub.clearcut.di.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScanProgress(
    val phase: String,
    val progress: Float?,
)

class ScannerViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as com.projecthub.clearcut.app.ClearCutApplication).container

    private val _scanState = MutableStateFlow<OperationState<ScanReport>>(OperationState.Idle)
    val scanState: StateFlow<OperationState<ScanReport>> = _scanState.asStateFlow()

    private val _media = MutableStateFlow<MediaInfo?>(null)
    val media: StateFlow<MediaInfo?> = _media.asStateFlow()

    private val _phase = MutableStateFlow("")
    val phase: StateFlow<String> = _phase.asStateFlow()

    private val _selectedFindingId = MutableStateFlow<String?>(null)
    val selectedFindingId: StateFlow<String?> = _selectedFindingId.asStateFlow()

    private val _filterCategory = MutableStateFlow<String?>(null)
    val filterCategory: StateFlow<String?> = _filterCategory.asStateFlow()

    private val _previewPositionUs = MutableStateFlow(0L)
    val previewPositionUs: StateFlow<Long> = _previewPositionUs.asStateFlow()

    private val _seekRequest = MutableStateFlow<Long?>(null)
    val seekRequest: StateFlow<Long?> = _seekRequest.asStateFlow()

    var scanJob: Job? = null
        private set

    private var phaseJob: Job? = null

    fun setMedia(media: MediaInfo) { _media.value = media }

    fun setPreviewPosition(us: Long) { _previewPositionUs.value = us }

    /** Called by the preview when it starts playing; clears stale seek requests. */
    fun consumeSeekRequest(): Long? = _seekRequest.value.also { _seekRequest.value = null }

    fun seekTo(us: Long) {
        _previewPositionUs.value = us
        _seekRequest.value = us
    }

    fun selectFinding(findingId: String?) {
        _selectedFindingId.value = findingId
        findingId?.let { id ->
            val f = currentReport()?.findings?.firstOrNull { it.id == id }
            if (f != null) seekTo(f.startUs)
        }
    }

    fun setFilter(category: String?) { _filterCategory.value = category }

    private val analysisPhases = listOf(
        "Analyzing audio",
        "Analyzing video",
        "Running structural analysis",
        "Merging analysis results",
    )

    /** Runs the full multi-method scan on the current media. */
    fun startScan(settings: ScanSettingsSnapshot) {
        if (_scanState.value.isActive || scanJob?.isActive == true) return
        val media = _media.value ?: return
        scanJob = viewModelScope.launch {
            _scanState.value = OperationState.InProgress("Preparing media", null)
            var tick = 0
            phaseJob = launch {
                while (true) {
                    delay(700)
                    tick++
                    _scanState.value = OperationState.InProgress(
                        analysisPhases[tick % analysisPhases.size],
                        null,
                    )
                }
            }
            try {
                val report = container.multiMethodScanner.scan(media, settings)
                phaseJob?.cancel()
                _scanState.value = OperationState.Completed(report)
                persistScan(report)
            } catch (e: ClearCutError) {
                phaseJob?.cancel()
                _scanState.value = OperationState.Failed(e)
            } catch (t: Throwable) {
                phaseJob?.cancel()
                _scanState.value = OperationState.Failed(ClearCutError.fromThrowable(t, "scan"))
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        phaseJob?.cancel()
        _scanState.value = OperationState.Cancelled("analysis")
    }

    private fun persistScan(report: ScanReport) {
        val media = _media.value ?: return
        viewModelScope.launch {
            val json = com.projecthub.clearcut.core.report.ReportGenerator.toJson(media, report, null, null)
            container.scanHistoryDao.insert(
                com.projecthub.clearcut.core.storage.ScanHistoryEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    projectId = media.uri,
                    mediaName = media.displayName,
                    scannedAtEpochMs = report.scannedAtEpochMs,
                    durationUs = report.mediaDurationUs,
                    findingCount = report.findings.size,
                    coverageFraction = report.coverage.analyzedFraction,
                    reportJson = json,
                )
            )
        }
    }

    fun currentReport(): ScanReport? = (_scanState.value as? OperationState.Completed)?.value
        ?: (_scanState.value as? OperationState.CompletedWithWarnings)?.value

    companion object {
        val Factory = viewModelFactory { app, _ -> ScannerViewModel(app) }
    }
}