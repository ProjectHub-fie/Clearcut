package com.projecthub.clearcut.core.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.projecthub.clearcut.core.model.SamplingIntensity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "clearcut_prefs")

data class AppPreferences(
    val darkTheme: Boolean? = null,          // null = follow system
    val scanWindowSeconds: Int = 20,
    val samplingIntensity: SamplingIntensity = SamplingIntensity.MEDIUM,
    val lowResourceMode: Boolean = false,
    val compareAudio: Boolean = true,
    val compareVisual: Boolean = true,
    val minConfidence: Float = 0.5f,
    val maxDownloadBytes: Long = 2L * 1024 * 1024 * 1024,
    val reduceMotion: Boolean = false,
)

class PreferencesStore(private val context: Context) {

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme_auto")
        val THEME_SYSTEM = booleanPreferencesKey("theme_system")
        val SCAN_WINDOW = intPreferencesKey("scan_window_seconds")
        val SAMPLING = intPreferencesKey("sampling_intensity")
        val LOW_RESOURCE = booleanPreferencesKey("low_resource_mode")
        val COMPARE_AUDIO = booleanPreferencesKey("compare_audio")
        val COMPARE_VISUAL = booleanPreferencesKey("compare_visual")
        val MIN_CONFIDENCE = floatPreferencesKey("min_confidence")
        val MAX_DOWNLOAD_BYTES = intPreferencesKey("max_download_bytes_mb")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
    }

    val preferences: Flow<AppPreferences> = context.dataStore.data.map { prefs ->
        AppPreferences(
            darkTheme = if (prefs[Keys.THEME_SYSTEM] != false) null else prefs[Keys.DARK_THEME] ?: false,
            scanWindowSeconds = prefs[Keys.SCAN_WINDOW] ?: 20,
            samplingIntensity = SamplingIntensity.entries
                .getOrElse(prefs[Keys.SAMPLING] ?: 1) { SamplingIntensity.MEDIUM },
            lowResourceMode = prefs[Keys.LOW_RESOURCE] ?: false,
            compareAudio = prefs[Keys.COMPARE_AUDIO] ?: true,
            compareVisual = prefs[Keys.COMPARE_VISUAL] ?: true,
            minConfidence = prefs[Keys.MIN_CONFIDENCE] ?: 0.5f,
            maxDownloadBytes = (prefs[Keys.MAX_DOWNLOAD_BYTES] ?: 2048).toLong() * 1024 * 1024,
            reduceMotion = prefs[Keys.REDUCE_MOTION] ?: false,
        )
    }

    suspend fun setTheme(dark: Boolean, followSystem: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_SYSTEM] = followSystem
            prefs[Keys.DARK_THEME] = dark
        }
    }

    suspend fun setScanWindow(seconds: Int) {
        context.dataStore.edit { it[Keys.SCAN_WINDOW] = seconds }
    }

    suspend fun setSampling(intensity: SamplingIntensity) {
        context.dataStore.edit { it[Keys.SAMPLING] = intensity.ordinal }
    }

    suspend fun setLowResource(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LOW_RESOURCE] = enabled }
    }

    suspend fun setCompareAudio(enabled: Boolean) {
        context.dataStore.edit { it[Keys.COMPARE_AUDIO] = enabled }
    }

    suspend fun setCompareVisual(enabled: Boolean) {
        context.dataStore.edit { it[Keys.COMPARE_VISUAL] = enabled }
    }

    suspend fun setMinConfidence(value: Float) {
        context.dataStore.edit { it[Keys.MIN_CONFIDENCE] = value }
    }

    suspend fun setMaxDownloadBytes(bytes: Long) {
        context.dataStore.edit { it[Keys.MAX_DOWNLOAD_BYTES] = ((bytes) / (1024 * 1024)).toInt() }
    }

    suspend fun setReduceMotion(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REDUCE_MOTION] = enabled }
    }
}