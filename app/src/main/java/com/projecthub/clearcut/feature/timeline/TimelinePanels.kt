package com.projecthub.clearcut.feature.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projecthub.clearcut.core.designsystem.ClearCutColors
import com.projecthub.clearcut.core.model.BlendMode
import com.projecthub.clearcut.core.model.ClipKind
import com.projecthub.clearcut.core.model.ExportPreset
import com.projecthub.clearcut.core.model.ProjectTimeline
import com.projecthub.clearcut.core.model.TextPreset
import com.projecthub.clearcut.core.model.TimelineClip
import com.projecthub.clearcut.core.model.TransitionKind

/** Presets mirroring the mobile-editor export resolutions. */
val exportPresets = listOf(
    ExportPreset(label = "4K 60fps", width = 3840, height = 2160, frameRateFps = 60, bitrateBps = 120_000_000, aspectRatioLabel = "16:9"),
    ExportPreset(label = "4K 30fps", width = 3840, height = 2160, frameRateFps = 30, bitrateBps = 80_000_000, aspectRatioLabel = "16:9"),
    ExportPreset(label = "1080p 60fps", width = 1920, height = 1080, frameRateFps = 60, bitrateBps = 30_000_000, aspectRatioLabel = "16:9"),
    ExportPreset(label = "1080p 30fps", width = 1920, height = 1080, frameRateFps = 30, bitrateBps = 16_000_000, aspectRatioLabel = "16:9"),
    ExportPreset(label = "720p 30fps", width = 1280, height = 720, frameRateFps = 30, bitrateBps = 8_000_000, aspectRatioLabel = "16:9"),
    ExportPreset(label = "Vertical 1080p", width = 1080, height = 1920, frameRateFps = 30, bitrateBps = 16_000_000, aspectRatioLabel = "9:16"),
    ExportPreset(label = "Square 1080p", width = 1080, height = 1080, frameRateFps = 30, bitrateBps = 12_000_000, aspectRatioLabel = "1:1"),
)

@Composable
internal fun TimelineToolbar(
    timeline: ProjectTimeline,
    vm: TimelineViewModel,
    onRequestPick: (ClipKind) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Add media",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(end = 4.dp),
            )
            ToolbarChip(Icons.Filled.Movie, "Video") { onRequestPick(ClipKind.VIDEO) }
            ToolbarChip(Icons.Filled.Image, "Image") { onRequestPick(ClipKind.IMAGE) }
            ToolbarChip(Icons.Filled.MusicNote, "Music") { onRequestPick(ClipKind.AUDIO) }
            ToolbarChip(Icons.Filled.TextFields, "Text") { vm.addTextClip() }
            ToolbarChip(Icons.Filled.ContentCopy, "Split") { vm.splitAtPlayhead() }
        }
    }
}

@Composable
private fun ToolbarChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.material3.AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
    )
}

@Composable
internal fun ClipOptionsPanel(clip: TimelineClip, vm: TimelineViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                clip.displayName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { vm.duplicateClip(clip.id) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { vm.deleteClip(clip.id) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp), tint = ClearCutColors.Error)
            }
        }

        // Tabs for controls
        val tabs = when (clip.kind) {
            ClipKind.TEXT -> listOf("Style", "Layout")
            ClipKind.VIDEO, ClipKind.IMAGE, ClipKind.PICTURE_OVERLAY -> listOf("Adjust", "Color", "Speed", "Layout")
            else -> listOf("Audio", "Speed")
        }
        var tab by remember(clip.id) { mutableStateOf(tabs.first()) }
        TabRow(selectedTabIndex = tab.let { tabs.indexOf(it).coerceAtLeast(0) }, containerColor = Color.Transparent) {
            tabs.forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t, style = MaterialTheme.typography.labelSmall) })
            }
        }
        Spacer(Modifier.height(8.dp))

        when (tab) {
            // ---- Video adjust (transform) ----
            "Adjust" -> {
                OptionRow("Opacity", value = clip.opacity) { vm.setOpacity(clip.id, it) }
                OptionRow("Blur", value = clip.blurRadius / 40f) { vm.setBlur(clip.id, it * 40f) }
                OptionRow("Fade in", value = clip.fadeInUs / 3000_000f) { vm.setFadeIn(clip.id, (it * 3000).toInt()) }
                OptionRow("Fade out", value = clip.fadeOutUs / 3000_000f) { vm.setFadeOut(clip.id, (it * 3000).toInt()) }
            }
            // ---- Color ----
            "Color" -> {
                OptionRow("Brightness", value = clip.colorGrade.brightness / 1f) { vm.setBrightness(clip.id, it) }
                OptionRow("Contrast", value = clip.colorGrade.contrast / 1f) { vm.setContrast(clip.id, it) }
                OptionRow("Saturation", value = clip.colorGrade.saturation / 100f) { vm.setSaturation(clip.id, it * 100f) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    FilterChip("B&W", clip.grayscale) { vm.setGrayscale(clip.id, !clip.grayscale) }
                    FilterChip("Invert", clip.invert) { vm.setInvert(clip.id, !clip.invert) }
                }
            }
            // ---- Speed ----
            "Speed" -> {
                OptionRow("Speed — ${"%.2f".format(clip.speed.speed)}x", value = (clip.speed.speed - 0.1f) / 3.9f) { vm.setSpeed(clip.id, 0.1f + it * 3.9f) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Checkbox(checked = clip.speed.pitchPreserved, onCheckedChange = { vm.setPreservePitch(clip.id, it) })
                    Text("Preserve pitch", style = MaterialTheme.typography.labelMedium)
                }
            }
            // ---- Layout (PiP / transform) ----
            "Layout" -> {
                OptionRow("Scale", value = clip.transform.scaleX / 1.5f) { vm.setPiPScale(clip.id, it * 1.5f) }
                OptionRow("Rotation", value = (clip.transform.rotationDegrees + 180f) / 360f) { vm.setPiPRotation(clip.id, it * 360f - 180f) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    BlendMode.entries.forEach { mode ->
                        FilterChip(mode.label, clip.blendMode == mode) { vm.setBlendMode(clip.id, mode) }
                    }
                }
            }
            // ---- Audio ----
            "Audio" -> {
                OptionRow("Volume", value = clip.volumeEnvelope.baseGain / 2f) { vm.setVolume(clip.id, it * 2f) }
                // Audio effect presets.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioEffectChip("None", clip.audioEffects.isEmpty()) {
                        if (clip.audioEffects.isNotEmpty()) vm.setAudioEffects(clip.id, emptyList())
                    }
                    com.projecthub.clearcut.core.model.AudioEffectType.entries.forEach { eff ->
                        AudioEffectChip(eff.label, clip.audioEffects.any { it.type == eff }) {
                            vm.setAudioEffects(
                                clip.id,
                                if (clip.audioEffects.firstOrNull { it.type == eff } != null) {
                                    clip.audioEffects.filterNot { it.type == eff }
                                } else {
                                    clip.audioEffects + com.projecthub.clearcut.core.model.AudioEffectPreset(eff, 0.5f)
                                },
                            )
                        }
                    }
                }
            }
            // ---- Text style ----
            "Style" -> {
                OutlinedTextField(
                    value = clip.text,
                    onValueChange = { vm.setText(clip.id, it) },
                    label = { Text("Text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OptionRow("Font size", value = clip.fontSizeSp / 72f) { vm.setFontSize(clip.id, it * 72f) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextPreset.entries.forEach { p ->
                        FilterChip(p.label, clip.textPreset == p) { vm.setTextPreset(clip.id, p) }
                    }
                }
            }
            // ---- Text layout (placeholder) ----
            "Layout" -> Unit
        }
    }
}

@Composable
private fun AudioEffectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, maxLines = 1) })
}

@Composable
private fun OptionRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
    )
}