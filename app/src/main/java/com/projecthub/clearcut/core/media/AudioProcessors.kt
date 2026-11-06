package com.projecthub.clearcut.core.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import com.projecthub.clearcut.core.model.AudioEffectPreset
import com.projecthub.clearcut.core.model.AudioEffectType
import com.projecthub.clearcut.core.model.VolumeEnvelope
import java.nio.ByteBuffer
import java.nio.ShortBuffer

/**
 * Base for our audio processors: implements the common output-buffer dance that
 * `BaseAudioProcessor` hides behind a private member. Each subclass writes into
 * the buffer returned by [replaceOutputBuffer] and then flips it.
 */
@androidx.media3.common.util.UnstableApi
abstract class ClearCutAudioProcessor : BaseAudioProcessor() {

    protected var currentOutput: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    protected fun reserveOutput(size: Int): java.nio.ShortBuffer {
        currentOutput = replaceOutputBuffer(size)
        return currentOutput.asShortBuffer()
    }

    protected fun emit() {
        currentOutput.flip()
    }
}

/**
 * Per-sample gain application for volume envelopes. The envelope is rendered once
 * per clip into a ramp array (one gain value per sample frame) so export-time
 * interpolation is constant-time.
 */
@androidx.media3.common.util.UnstableApi
class GainAudioProcessor(
    private val gainRamp: FloatArray,
    private val constantGain: Float = 1f,
) : ClearCutAudioProcessor() {

    private val rampsToIdentity: Boolean = gainRamp.all { it == 1f }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        super.isActive() && !(constantGain == 1f && rampsToIdentity)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = if (inputAudioFormat.channelCount > 0) inputAudioFormat.channelCount else 2
        val frameBytes = channels * 2
        val frames = inputBuffer.remaining() / frameBytes
        if (frames <= 0) return
        val dst = reserveOutput(inputBuffer.remaining())
        val src = inputBuffer.asShortBuffer()
        var sampleIndex = 0
        while (src.hasRemaining()) {
            val gain = if (sampleIndex < gainRamp.size) gainRamp[sampleIndex] * constantGain else constantGain
            val scaled = (src.get().toInt() * gain).toInt()
            dst.put(scaled.coerceIn(-32768, 32767).toShort())
            sampleIndex += channels
        }
        emit()
    }

    companion object {
        /** Render a [VolumeEnvelope] into per-frame gain values for [frameCount] frames. */
        @JvmStatic
        fun buildRamp(envelope: VolumeEnvelope, frameCount: Int, sampleRate: Int): FloatArray {
            val ramp = FloatArray(frameCount)
            for (i in 0 until frameCount) {
                val fraction = if (frameCount <= 1) 0f else i.toFloat() / (frameCount - 1)
                ramp[i] = envelope.gainAt(fraction).coerceIn(0f, 4f)
            }
            return ramp
        }
    }
}

/**
 * Simple feedback echo, implementing the "Echo / Reverb" audio effects as an
 * AudioProcessor so it applies during export, not only in the UI.
 */
@androidx.media3.common.util.UnstableApi
class EchoAudioProcessor(
    private val echoDelayMs: Int = 320,
    private val feedback: Float = 0.35f,
    private val wet: Float = 0.55f,
) : ClearCutAudioProcessor() {
    private var delayLine = FloatArray(0)
    private var cursor = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        val channels = if (inputAudioFormat.channelCount > 0) inputAudioFormat.channelCount else 2
        val delayFrames = (echoDelayMs.toDouble() * inputAudioFormat.sampleRate / 1000.0).toInt().coerceAtLeast(1)
        delayLine = FloatArray(delayFrames * channels)
        cursor = 0
        return inputAudioFormat
    }

    override fun isActive(): Boolean = super.isActive() && feedback > 0f && wet > 0f

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = if (inputAudioFormat.channelCount > 0) inputAudioFormat.channelCount else 2
        val frameBytes = channels * 2
        val frames = inputBuffer.remaining() / frameBytes
        if (frames <= 0 || delayLine.isEmpty()) return
        val dst = reserveOutput(inputBuffer.remaining())
        val src = inputBuffer.asShortBuffer()
        var i = 0
        while (src.hasRemaining()) {
            val dry = src.get().toFloat() / 32768f
            val idx = (cursor + i) % delayLine.size
            val delayed = delayLine[idx]
            val out = dry * (1f - wet) + delayed * wet
            delayLine[idx] = dry + delayed * feedback
            dst.put((out.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            i++
        }
        cursor = (cursor + i) % delayLine.size
        emit()
    }
}

/**
 * First-order low/high-pass filters used by the equalizer / tone shaping tools.
 */
@androidx.media3.common.util.UnstableApi
class BiquadAudioProcessor(
    private val type: FilterType,
    private val cutoffHz: Float,
    private val q: Float = 0.707f,
) : ClearCutAudioProcessor() {

    enum class FilterType { LOWPASS, HIGHPASS }

    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        val fs = inputAudioFormat.sampleRate.toDouble()
        val w0 = 2.0 * Math.PI * cutoffHz / fs
        val cosw = Math.cos(w0)
        val alpha = Math.sin(w0) / (2.0 * q)
        when (type) {
            FilterType.LOWPASS -> {
                b0 = (1.0 - cosw) / 2.0
                b1 = 1.0 - cosw
                b2 = b0
                a1 = -2.0 * cosw
                a2 = 1.0 - alpha
                val a0 = 1.0 + alpha
                b0 /= a0
                b1 /= a0
                b2 /= a0
                a1 /= a0
                a2 /= a0
            }
            FilterType.HIGHPASS -> {
                b0 = (1.0 + cosw) / 2.0
                b1 = -(1.0 + cosw)
                b2 = b0
                a1 = -2.0 * cosw
                a2 = 1.0 - alpha
                val a0 = 1.0 + alpha
                b0 /= a0
                b1 /= a0
                b2 /= a0
                a1 /= a0
                a2 /= a0
            }
        }
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        return inputAudioFormat
    }

    override fun isActive(): Boolean = super.isActive() && cutoffHz > 0f

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = if (inputAudioFormat.channelCount > 0) inputAudioFormat.channelCount else 2
        val frameBytes = channels * 2
        val frames = inputBuffer.remaining() / frameBytes
        if (frames <= 0) return
        val dst = reserveOutput(inputBuffer.remaining())
        val src = inputBuffer.asShortBuffer()
        // One filter state per channel; audio can be interleaved stereo.
        val states = Array(channels) { DoubleArray(4) } // {x1, x2, y1, y2}
        var channel = 0
        while (src.hasRemaining()) {
            val s0 = src.get().toDouble() / 32768.0
            val st = states[channel]
            val y = b0 * s0 + b1 * st[0] + b2 * st[1] - a1 * st[2] - a2 * st[3]
            st[1] = st[0]; st[0] = s0
            st[3] = st[2]; st[2] = y
            dst.put((y.coerceIn(-1.0, 1.0) * 32767).toInt().toShort())
            channel = (channel + 1) % channels
        }
        x1 = states[0][0]; x2 = states[0][1]; y1 = states[0][2]; y2 = states[0][3]
        emit()
    }
}

/**
 * Pitch shifting via Sonic, powering pitch-shift / chipmunk / robot presets.
 *
 * Sonic buffers internally, so on end-of-stream we drain whatever remains into
 * one final output buffer. Each `queueInput` call concatenates all intermediate
 * chunks into a single output buffer as well (Sonic may emit in bursts).
 */
@androidx.media3.common.util.UnstableApi
class PitchShiftAudioProcessor(
    private val pitchMultiplier: Float = 1f,
) : ClearCutAudioProcessor() {

    private val sonic = SonicAudioProcessor()
    private val pending = java.io.ByteArrayOutputStream()

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        pending.reset()
        sonic.flush()
        sonic.setPitch(pitchMultiplier)
        return sonic.configure(inputAudioFormat)
    }

    override fun isActive(): Boolean = super.isActive() && pitchMultiplier != 1f

    private fun drainSonic() {
        while (true) {
            val chunk = sonic.getOutput()
            if (chunk.remaining() == 0) break
            val bytes = ByteArray(chunk.remaining())
            chunk.get(bytes)
            pending.write(bytes)
        }
        if (pending.size() > 0) {
            val out = pending.toByteArray()
            pending.reset()
            currentOutput = replaceOutputBuffer(out.size)
            currentOutput.put(out)
            currentOutput.flip()
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        sonic.queueInput(inputBuffer)
        drainSonic()
    }

    override fun onQueueEndOfStream() {
        pending.reset()
        sonic.queueEndOfStream()
        drainSonic()
    }
}

/** Build the ordered processors for a clip's audio effects. */
@androidx.media3.common.util.UnstableApi
fun buildAudioEffects(
    effects: List<AudioEffectPreset>,
): List<AudioProcessor> {
    val processors = mutableListOf<AudioProcessor>()
    for (effect in effects) {
        when (effect.type) {
            AudioEffectType.ECHO ->
                processors += EchoAudioProcessor(330, (0.25f + 0.5f * effect.intensity).coerceIn(0f, 0.8f), 0.55f)
            AudioEffectType.REVERB ->
                processors += EchoAudioProcessor(120, (0.3f + 0.5f * effect.intensity).coerceIn(0f, 0.85f), 0.6f)
            AudioEffectType.LOWPASS -> {
                val freq = (4000f - 3000f * effect.intensity).coerceIn(400f, 4000f)
                processors += BiquadAudioProcessor(BiquadAudioProcessor.FilterType.LOWPASS, freq)
            }
            AudioEffectType.HIGHPASS -> {
                val freq = (150f + 2800f * effect.intensity).coerceIn(150f, 4000f)
                processors += BiquadAudioProcessor(BiquadAudioProcessor.FilterType.HIGHPASS, freq)
            }
            AudioEffectType.PITCH_SHIFT -> {
                val multiplier = Math.pow(2.0, effect.pitchSemitones / 12.0).toFloat()
                processors += PitchShiftAudioProcessor(multiplier)
            }
            AudioEffectType.EQUALIZER -> {
                processors += BiquadAudioProcessor(BiquadAudioProcessor.FilterType.HIGHPASS, 900f + 1600f * effect.intensity)
            }
            AudioEffectType.ROBOT -> {
                processors += BiquadAudioProcessor(BiquadAudioProcessor.FilterType.LOWPASS, 850f)
                processors += PitchShiftAudioProcessor(0.9f)
            }
            AudioEffectType.CHIPMUNK -> {
                processors += PitchShiftAudioProcessor(1.45f)
            }
        }
    }
    return processors
}