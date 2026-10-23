package com.projecthub.clearcut.feature.export

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.projecthub.clearcut.core.media.EditedMediaValidator
import com.projecthub.clearcut.core.media.ExportForegroundService
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.Edl
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.OperationState
import com.projecthub.clearcut.core.model.RenderRequest
import com.projecthub.clearcut.di.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class ExportResultData(
    val path: String,
    val durationUs: Long,
    val sizeBytes: Long,
    val validationWarnings: List<String>,
    val validated: Boolean,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExportViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as com.projecthub.clearcut.app.ClearCutApplication).container

    private val _exportState = MutableStateFlow<OperationState<ExportResultData>>(OperationState.Idle)
    val exportState: StateFlow<OperationState<ExportResultData>> = _exportState.asStateFlow()

    private var media: MediaInfo? = null
    private var edl: Edl? = null

    fun start(source: MediaInfo, edl: Edl) {
        media = source
        this.edl = edl
        if (_exportState.value.isActive) return
        val outputFile = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "ClearCut_${source.displayName}_${System.currentTimeMillis()}.mp4")
        val request = RenderRequest(
            sourceUri = source.uri,
            edl = edl,
            outputPath = outputFile.absolutePath,
            expectedSizeBytes = (source.sizeBytes * 0.9).toLong().coerceAtLeast(1),
        )
        viewModelScope.launch {
            // Use foreground service for long-running render, then validate result.
            _exportState.value = OperationState.Validating("Preparing export…")
            // HTTP-like state machine; actual rendering happens in the service.
            ExportForegroundService.start(getApplication(), source.uri, edl, outputFile.absolutePath)
            _exportState.value = OperationState.InProgress("Rendering export", null)
        }
    }

    /** Called by the receiver listening for BroadcastNotifier events. */
    fun onRenderCompleted(path: String) {
        viewModelScope.launch {
            _exportState.value = OperationState.Validating("Validating exported file…")
            val output = File(path)
            if (!output.exists()) {
                _exportState.value = OperationState.Failed(ClearCutError.RenderError("The output file is missing after export."))
                return@launch
            }
            try {
                val result = EditedMediaValidator.validate(getApplication(), output, media)
                val data = ExportResultData(
                    path = path,
                    durationUs = result.durationUs,
                    sizeBytes = output.length(),
                    validationWarnings = result.warnings,
                    validated = result.validated,
                )
                _exportState.value = if (result.warnings.isEmpty()) {
                    OperationState.Completed(data)
                } else {
                    OperationState.CompletedWithWarnings(data, result.warnings)
                }
            } catch (e: Throwable) {
                _exportState.value = OperationState.Failed(ClearCutError.fromThrowable(e, "export validation"))
            }
        }
    }

    fun onRenderError(error: ClearCutError) {
        _exportState.value = OperationState.Failed(error)
    }

    fun cancelExport() {
        ExportForegroundService.cancel(getApplication())
        _exportState.value = OperationState.Cancelled("rendering")
    }

    fun retry() {
        val media = media ?: return
        val edl = edl ?: return
        _exportState.value = OperationState.Idle
        start(media, edl)
    }

    companion object {
        val Factory = viewModelFactory { app, _ -> ExportViewModel(app) }
    }
}