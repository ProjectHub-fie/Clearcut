package com.projecthub.clearcut.feature.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.projecthub.clearcut.core.model.StorageUsage
import com.projecthub.clearcut.core.scanner.MultiMethodScanner
import com.projecthub.clearcut.di.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val storageUsage: StorageUsage? = null,
    val hasActiveScan: Boolean = false,
    val activeScanPhase: String? = null,
    val activeJobCount: Int = 0,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as com.projecthub.clearcut.app.ClearCutApplication).container

    val projects = container.projectDao.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val scanHistory = container.scanHistoryDao.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val usage = com.projecthub.clearcut.core.storage.StorageHelper.usage(getApplication())
            _uiState.value = _uiState.value.copy(storageUsage = usage)
        }
    }

    fun updateJob(phase: String?) {
        _uiState.value = _uiState.value.copy(
            hasActiveScan = phase != null,
            activeScanPhase = phase,
            activeJobCount = if (phase != null) 1 else 0,
        )
    }

    companion object {
        val Factory = viewModelFactory { app, _ -> HomeViewModel(app) }
    }
}