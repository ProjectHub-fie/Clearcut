package com.projecthub.clearcut.core.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Records a voiceover narration clip using [MediaRecorder] into the app's
 * cache directory, emitting mic metering while running.
 */
class VoiceoverRecorder(private val context: Context) {

    sealed interface State {
        object Idle : State
        data class Recording(val file: File, val elapsedMs: Long, val amplitude: Int) : State
        data class Finished(val file: File, val durationMs: Long) : State
    }

    private val micPermission = Manifest.permission.RECORD_AUDIO
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, micPermission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (_state.value !is State.Idle || !hasPermission) return false
        val file = File(context.cacheDir, "voiceover_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
            }
            rec.prepare()
            rec.start()
            recorder = rec
            outputFile = file
            startedAt = SystemClock.elapsedRealtime()
            _state.value = State.Recording(file, 0L, 0)
            true
        } catch (e: Exception) {
            runCatching { rec.release() }
            false
        }
    }

    /** Tick the recording state (called ~4Hz from the UI) and emit metering. */
    fun tick() {
        val s = _state.value as? State.Recording ?: return
        val amp = recorder?.maxAmplitude ?: 0
        _state.value = State.Recording(s.file, SystemClock.elapsedRealtime() - startedAt, amp)
    }

    fun stop(): State? {
        val s = _state.value as? State.Recording ?: return null
        val duration = SystemClock.elapsedRealtime() - startedAt
        return try {
            recorder?.stop()
            val finished = State.Finished(s.file, duration)
            _state.value = finished
            finished
        } catch (e: RuntimeException) {
            _state.value = State.Idle
            null
        } finally {
            recorder?.release()
            recorder = null
        }
    }

    fun cancel() {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { outputFile?.delete() }
        outputFile = null
        _state.value = State.Idle
    }

    fun reset() {
        _state.value = State.Idle
    }
}