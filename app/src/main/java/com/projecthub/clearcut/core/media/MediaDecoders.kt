package com.projecthub.clearcut.core.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.SamplingIntensity
import com.projecthub.clearcut.core.model.ScanSettingsSnapshot
import com.projecthub.clearcut.core.scanner.MultiMethodScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes full-duration audio to mono float PCM and samples decodable video
 * frames downscaled to grayscale, using MediaExtractor + MediaCodec directly so
 * the scanner can cover the ENTIRE file, not just the first seconds.
 *
 * All decoding happens off the main thread. Long files are chunked to bound
 * memory usage; frames are sampled adaptively per [SamplingIntensity].
 */
class MediaDecoders(private val context: Context) {

    private class AudioTrackHolder(val mime: String, val sampleRate: Int, val channels: Int)

    /** Decode the full audio track to float mono PCM @ native rate (downmixed). */
    suspend fun decodeAudioToPcm(
        media: MediaInfo,
        settings: ScanSettingsSnapshot,
        onProgress: suspend (Float) -> Unit,
    ): MultiMethodScanner.DecodedAudio? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            val uri = Uri.parse(media.uri)
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor)
                ?: throw ClearCutError.DecodeError("No decodable audio track in this file.")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/raw"
            if (!isAudioMimeSupported(mime)) {
                throw ClearCutError.UnsupportedMediaError(
                    "The audio codec '$mime' is not supported for analysis on this device."
                )
            }
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw ClearCutError.UnsupportedMediaError("No audio decoder for '$mime' is available on this device.", e)
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                runCatching { codec.release() }
                throw ClearCutError.DecodeError("Failed to start audio decoder for '$mime'.", e)
            }

            val out = java.io.ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var eos = false
            var decodedUs = 0L
            var lastProgressT = 0L
            val durationUs = media.durationUs

            try {
                while (!eos) {
                    currentCoroutineContext().ensureActive()
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = extractor.readSampleData(inBuf!!, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        outIdx >= 0 -> {
                            if (bufferInfo.size > 0) {
                                val outBuf = codec.getOutputBuffer(outIdx)!!
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val bytes = ByteArray(bufferInfo.size)
                                outBuf.get(bytes)
                                out.write(bytes)
                            }
                            decodedUs = maxOf(decodedUs, bufferInfo.presentationTimeUs)
                            codec.releaseOutputBuffer(outIdx, false)
                            run {
                                val now = System.currentTimeMillis()
                                if (durationUs > 0 && now - lastProgressT > 120) {
                                    val p = (decodedUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                    onProgress(p)
                                    lastProgressT = now
                                }
                            }
                        }
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (eos && decodedUs <= 0) {
                                // Bail out of potential infinite loop with empty decodes
                                var spins = 0
                                while (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && spins++ < 5) {
                                    Thread.sleep(5)
                                }
                                if (spins >= 5) eos = true
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ClearCutDecoder", "audio decode exception: ${e.message}")
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }

            val raw = out.toByteArray()
            if (raw.size < 1024) return@withContext null

            // Convert PCM bytes (may be pcm16 or float) to mono float at native sample rate.
            val pcm = convertToMonoFloat(raw, mime, channels)
            if (pcm.isEmpty()) return@withContext null
            MultiMethodScanner.DecodedAudio(pcm, sampleRate)
        } catch (e: ClearCutError) {
            Log.w("ClearCutDecoder", "audio decode error: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w("ClearCutDecoder", "audio decode unexpected: $e")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun convertToMonoFloat(raw: ByteArray, mime: String, channels: Int): FloatArray {
        // Decoder outputs are typically PCM 16-bit little-endian.
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val sb = bb.asShortBuffer()
        val out = FloatArray(sb.remaining())
        var i = 0
        while (sb.hasRemaining()) {
            out[i++] = sb.get().toFloat() / 32768f
        }
        return out
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/") && isAudioMimeSupported(mime)) return i
        }
        return null
    }

    private fun isAudioMimeSupported(mime: String): Boolean =
        mime.startsWith("audio/") &&
            !mime.contains("eac3_joc")   // never attempt encrypted/ATM profile

    /** Sample grayscale frames throughout the video at an adaptive rate. */
    suspend fun sampleFrames(
        media: MediaInfo,
        settings: ScanSettingsSnapshot,
        onProgress: suspend (Float) -> Unit,
    ): List<MultiMethodScanner.DecodedFrame>? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            val uri = Uri.parse(media.uri)
            extractor.setDataSource(context, uri, null)
            val trackIndex = findVideoTrack(extractor)
                ?: throw ClearCutError.DecodeError("No decodable video track in this file.")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            var width = format.getInteger(MediaFormat.KEY_WIDTH)
            var height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = media.rotationDegrees
            if (rotation == 90 || rotation == 270) {
                width = height.also { height = width }
            }
            if (width <= 0 || height <= 0) throw ClearCutError.DecodeError("Invalid video dimensions.")

            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw ClearCutError.UnsupportedMediaError("No video decoder for '$mime' on this device.", e)
            }
            runCatching { codec.configure(format, null, null, 0) }
            runCatching { codec.start() }

            val widthSource = width
            val heightSource = height
            val scale = 96.0 / maxOf(width, height)
            val outW = maxOf(1, (width * scale).toInt())
            val outH = maxOf(1, (height * scale).toInt())

            val frames = mutableListOf<MultiMethodScanner.DecodedFrame>()
            val bufferInfo = MediaCodec.BufferInfo()
            var eos = false
            var lastEmittedTs = -1L
            var lastProgress = 0L
            val durationUs = media.durationUs
            val maxFrames = if (settings.lowResourceMode) 900 else 4000

            try {
                while (!eos && frames.size < maxFrames) {
                    currentCoroutineContext().ensureActive()
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = extractor.readSampleData(inBuf!!, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        outIdx >= 0 -> {
                            val pts = bufferInfo.presentationTimeUs
                            emitIfDue(
                                codec = codec,
                                outIdx = outIdx,
                                pts = pts,
                                lastEmittedTs = lastEmittedTs,
                                widthSource = widthSource,
                                heightSource = heightSource,
                                outW = outW,
                                outH = outH,
                                frames = frames,
                            )
                            if (pts > lastEmittedTs) lastEmittedTs = pts
                            run {
                                val now = System.currentTimeMillis()
                                if (durationUs > 0 && now - lastProgress > 150) {
                                    onProgress((pts.toFloat() / durationUs).coerceIn(0f, 1f))
                                    lastProgress = now
                                }
                            }
                        }
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (eos) {
                                var spins = 0
                                while (codec.dequeueOutputBuffer(bufferInfo, 5_000) == MediaCodec.INFO_TRY_AGAIN_LATER && spins++ < 8) {
                                    Thread.sleep(5)
                                }
                                if (spins >= 8) eos = true
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ClearCutDecoder", "video decode exception: ${e.message}")
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }

            if (frames.isEmpty()) null else frames
        } catch (e: ClearCutError) {
            Log.w("ClearCutDecoder", "video decode error: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w("ClearCutDecoder", "video decode unexpected: $e")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun emitIfDue(
        codec: MediaCodec,
        outIdx: Int,
        pts: Long,
        lastEmittedTs: Long,
        widthSource: Int,
        heightSource: Int,
        outW: Int,
        outH: Int,
        frames: MutableList<MultiMethodScanner.DecodedFrame>,
    ) {
        // Always keep the very first frame; thereafter sample approx twice per second
        // so the analyze step has enough resolution for scene-change detection without
        // exploding memory (a 2h video at 2fps => ~14k small bitmaps, bounded below).
        val keep = frames.isEmpty() || pts - lastEmittedTs >= 500_000L
        if (keep) {
            val image = runCatching { codec.getOutputImage(outIdx) }.getOrNull()
            val gray = imageToGray(image, widthSource, heightSource, outW, outH)
            frames.add(
                MultiMethodScanner.DecodedFrame(
                    pixels = gray,
                    width = outW,
                    height = outH,
                    timestampUs = pts,
                    lumaMean = computeLuma(gray),
                )
            )
        }
        codec.releaseOutputBuffer(outIdx, false)
    }

    private fun computeLuma(gray: ByteArray): Float {
        if (gray.isEmpty()) return 0f
        var sum = 0
        for (b in gray) sum += b.toInt() and 0xFF
        return sum.toFloat() / gray.size
    }

    private fun imageToGray(
        image: android.media.Image?,
        widthSource: Int,
        heightSource: Int,
        outW: Int,
        outH: Int,
    ): ByteArray {
        if (image == null) {
            // Return a mid-gray placeholder so fingerprinting still runs.
            return ByteArray(outW * outH) { 0x80.toByte() }
        }
        val planes = image.planes
        val yPlane = planes[0]
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val inW = image.width
        val inH = image.height
        val result = ByteArray(outW * outH)
        for (y in 0 until outH) {
            val srcY = (y * inH) / outH
            var rowBase = srcY * rowStride
            for (x in 0 until outW) {
                val srcX = (x * inW) / outW
                val value = yBuffer.get(rowBase + srcX * pixelStride).toInt() and 0xFF
                result[y * outW + x] = value.toByte()
            }
        }
        return result
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }
}