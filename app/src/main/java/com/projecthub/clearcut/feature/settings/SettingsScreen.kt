package com.projecthub.clearcut.feature.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.projecthub.clearcut.core.model.SamplingIntensity
import com.projecthub.clearcut.core.storage.AppPreferences
import com.projecthub.clearcut.di.AppContainer
import com.projecthub.clearcut.di.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val prefs: StateFlow<AppPreferences?> = container.preferencesStore.preferences.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), null,
    )

    fun setTheme(dark: Boolean, followSystem: Boolean) = viewModelScope.launch { container.preferencesStore.setTheme(dark, followSystem) }
    fun setSampling(s: SamplingIntensity) = viewModelScope.launch { container.preferencesStore.setSampling(s) }
    fun setLowResource(enabled: Boolean) = viewModelScope.launch { container.preferencesStore.setLowResource(enabled) }
    fun setCompareAudio(enabled: Boolean) = viewModelScope.launch { container.preferencesStore.setCompareAudio(enabled) }
    fun setCompareVisual(enabled: Boolean) = viewModelScope.launch { container.preferencesStore.setCompareVisual(enabled) }
    fun setMinConfidence(value: Float) = viewModelScope.launch { container.preferencesStore.setMinConfidence(value) }
    fun setReduceMotion(enabled: Boolean) = viewModelScope.launch { container.preferencesStore.setReduceMotion(enabled) }

    fun clearHistory() = viewModelScope.launch { container.scanHistoryDao.clearAll() }

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

            item { SectionTitle("About & limitations") }
            item {
                PremiumCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("ClearCut 1.0.0", style = MaterialTheme.typography.titleSmall)
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