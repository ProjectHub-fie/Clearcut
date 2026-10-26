package com.projecthub.clearcut.feature.settings

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projecthub.clearcut.core.designsystem.ClearCutColors
import com.projecthub.clearcut.core.designsystem.PremiumCard
import com.projecthub.clearcut.core.designsystem.userMessage
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.SamplingIntensity
import com.projecthub.clearcut.core.storage.AppPreferences
import com.projecthub.clearcut.core.updates.ReleaseInfo
import com.projecthub.clearcut.di.AppContainer
import com.projecthub.clearcut.di.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the "Check for update" section. */
sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Available(val release: ReleaseInfo, val apkAsset: ReleaseInfo.Asset?) : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data class Downloading(val progress: Float) : UpdateCheckState
    data class DownloadReady(val release: ReleaseInfo, val apkPath: String) : UpdateCheckState
    data class Failed(val error: ClearCutError) : UpdateCheckState
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val prefs: StateFlow<AppPreferences?> = container.preferencesStore.preferences.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), null,
    )

    private val _updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateState: StateFlow<UpdateCheckState> = _updateState

    fun setTheme(dark: Boolean, followSystem: Boolean) = viewModelScope.launch { container.preferencesStore.setTheme(dark, followSystem) }
    fun setSampling(s: SamplingIntensity) = viewModelScope.launch { container.preferencesStore.setSampling(s) }
    fun setLowResource(enabled: Boolean) = viewModelScope.launch { container.preferencesStore.setLowResource(enabled) }
    fun setCompareAudio(enabled: Boolean) = viewModelScope.launch { container.preferencesStore.setCompareAudio(enabled) }
    fun setCompareVisual(enabled: Boolean) = viewModelScope.launch { container.preferencesStore.setCompareVisual(enabled) }
    fun setMinConfidence(value: Float) = viewModelScope.launch { container.preferencesStore.setMinConfidence(value) }
    fun setReduceMotion(enabled: Boolean) = viewModelScope.launch { container.preferencesStore.setReduceMotion(enabled) }

    fun clearHistory() = viewModelScope.launch { container.scanHistoryDao.clearAll() }

    /** Version of the currently installed APK. */
    val appVersion: String get() = container.appVersionName.ifBlank { "1.0.0" }

    fun checkForUpdate() {
        if (_updateState.value == UpdateCheckState.Checking || _updateState.value is UpdateCheckState.Downloading) return
        viewModelScope.launch {
            _updateState.value = UpdateCheckState.Checking
            _updateState.value = try {
                val release = container.updateChecker.checkForUpdate(appVersion)
                if (release == null) {
                    UpdateCheckState.UpToDate
                } else {
                    val apk = release.assets.firstOrNull { it.isApk }
                    UpdateCheckState.Available(release, apk)
                }
            } catch (e: ClearCutError) {
                UpdateCheckState.Failed(e)
            } catch (e: Exception) {
                UpdateCheckState.Failed(ClearCutError.NetworkError("Could not check for updates.", e))
            }
        }
    }

    fun downloadUpdate(release: ReleaseInfo, apkUrl: String, expectedBytes: Long) {
        if (_updateState.value == UpdateCheckState.Checking || _updateState.value is UpdateCheckState.Downloading) return
        viewModelScope.launch {
            _updateState.value = UpdateCheckState.Downloading(0f)
            try {
                val outcome = container.updateManager.downloadApk(apkUrl, expectedBytes) { progress ->
                    _updateState.value = UpdateCheckState.Downloading(progress)
                }
                _updateState.value = UpdateCheckState.DownloadReady(release, outcome.apkFile.absolutePath)
            } catch (e: ClearCutError) {
                _updateState.value = UpdateCheckState.Failed(e)
            } catch (e: Exception) {
                _updateState.value = UpdateCheckState.Failed(ClearCutError.NetworkError("The update download failed.", e))
            }
        }
    }

    fun installUpdate(apkPath: String) {
        try {
            container.updateManager.installApk(java.io.File(apkPath))
        } catch (e: ClearCutError) {
            _updateState.value = UpdateCheckState.Failed(e)
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateCheckState.Idle
    }

    companion object {
        val Factory = viewModelFactory { _, c -> SettingsViewModel(c) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val prefs by vm.prefs.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val updateState by vm.updateState.collectAsState()

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                modifier = Modifier,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionTitle("Appearance") }
            item {
                val p = prefs
                if (p != null) {
                    PremiumCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Theme", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            ThemeOption("System default", selected = p.darkTheme == null, onClick = { vm.setTheme(systemDark, true) })
                            ThemeOption("Dark", selected = p.darkTheme == true, onClick = { vm.setTheme(true, false) })
                            ThemeOption("Light", selected = p.darkTheme == false, onClick = { vm.setTheme(false, false) })
                        }
                    }
                }
            }

            item { SectionTitle("Analysis preferences") }
            item {
                val p = prefs
                if (p != null) {
                    PremiumCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Sampling intensity", style = MaterialTheme.typography.titleSmall)
                            SamplingIntensity.entries.forEach { s ->
                                OptionRow(
                                    label = "${s.label} (${s.stepSeconds}s step)",
                                    selected = p.samplingIntensity == s,
                                    onClick = { vm.setSampling(s) },
                                )
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = ClearCutColors.OutlineDark.copy(alpha = 0.5f))
                            SwitchRow("Compare audio", p.compareAudio) { vm.setCompareAudio(it) }
                            SwitchRow("Compare visual", p.compareVisual) { vm.setCompareVisual(it) }
                            SwitchRow("Low-resource mode", p.lowResourceMode) { vm.setLowResource(it) }
                            SwitchRow("Respect reduced motion", p.reduceMotion) { vm.setReduceMotion(it) }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Confidence threshold: ${(p.minConfidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = ClearCutColors.OnInkDim,
                            )
                            Slider(
                                value = p.minConfidence,
                                onValueChange = { vm.setMinConfidence(it) },
                                valueRange = 0.1f..0.9f,
                            )
                        }
                    }
                }
            }

            item { SectionTitle("Privacy") }
            item {
                PremiumCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "ClearCut analyzes media on-device. Nothing is uploaded unless you explicitly authorize it. Internet access is only used when you import from a URL.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClearCutColors.OnInkDim,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { vm.clearHistory() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Clear scan history and local indexes")
                        }
                    }
                }
            }

            item { SectionTitle("Updates") }
            item {
                PremiumCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("App updates", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Check GitHub Releases for a newer ClearCut and install it in-place. The check is read-only; downloads are streamed into ClearCut's private cache and only launched through the system install prompt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClearCutColors.OnInkDim,
                        )
                        Spacer(Modifier.height(12.dp))
                        UpdateSection(
                            vm = vm,
                            state = updateState,
                            currentVersion = vm.appVersion,
                        )
                    }
                }
            }

            item { SectionTitle("About & limitations") }
            item {
                PremiumCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("ClearCut ${vm.appVersion}", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Database-free analysis detects repeated and similar passages within the media you analyze. It cannot identify every copyrighted work and never guarantees copyright clearance or avoidance of platform detection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClearCutColors.OnInkDim,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "HTML video pages from providers such as YouTube and Facebook are not downloaded. Only direct media links or official provider integrations are used.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ClearCutColors.OnInkDim,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

@Composable
private fun UpdateSection(
    vm: SettingsViewModel,
    state: UpdateCheckState,
    currentVersion: String,
) {
    when (state) {
        UpdateCheckState.Idle -> OutlinedButton(onClick = { vm.checkForUpdate() }, modifier = Modifier.fillMaxWidth()) {
            Text("Check for updates")
        }

        UpdateCheckState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Checking for updates…", style = MaterialTheme.typography.bodyMedium)
        }

        UpdateCheckState.UpToDate -> Column {
            Text("You're on the latest version.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.checkForUpdate() }, modifier = Modifier.fillMaxWidth()) {
                Text("Check again")
            }
        }

        is UpdateCheckState.Available -> {
            val release = state.release
            val asset = state.apkAsset
            Column {
                Text(
                    "ClearCut ${release.semver} is available (you have $currentVersion).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (asset != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Size: ${asset.sizeBytes.formatSize()} · Published ${release.publishedAt?.take(10) ?: "recently"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClearCutColors.OnInkDim,
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (asset != null) {
                    Button(onClick = {
                        vm.downloadUpdate(release, asset.browserDownloadUrl, asset.sizeBytes)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Download & install update")
                    }
                } else {
                    OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false) {
                        Text("No APK attached to this release")
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { vm.dismissUpdate() }) { Text("Dismiss") }
            }
        }

        is UpdateCheckState.Downloading -> {
            val progress = state.progress
            Column {
                Text("Downloading update…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = ClearCutColors.Accent,
                    trackColor = ClearCutColors.Accent.copy(alpha = 0.15f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = ClearCutColors.OnInkDim,
                )
            }
        }

        is UpdateCheckState.DownloadReady -> Column {
            Text(
                "ClearCut v${state.release.semver} is downloaded. Tap below to begin the system install.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { vm.installUpdate(state.apkPath) }, modifier = Modifier.fillMaxWidth()) {
                Text("Install update")
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { vm.dismissUpdate() }) { Text("Later") }
        }

        is UpdateCheckState.Failed -> Column {
            Text(
                state.error.userMessage(),
                style = MaterialTheme.typography.bodySmall,
                color = ClearCutColors.Error,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.checkForUpdate() }, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
        }
    }
}

private fun Long.formatSize(): String = when {
    this >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", this / (1024.0 * 1024.0))
    this >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", this / 1024.0)
    else -> "$this B"
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}