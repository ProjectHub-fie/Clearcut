@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.projecthub.clearcut.core.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.projecthub.clearcut.core.model.ClearCutError
import com.projecthub.clearcut.core.model.EditOperationType
import com.projecthub.clearcut.core.model.RenderRequest
import com.projecthub.clearcut.core.model.TimeRange
import com.projecthub.clearcut.core.model.ValidationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renders an [Edl] to an MP4 (H.264/AAC) using Media3 Transformer.
 *
 * Cut operations become ExoPlayer sequence-edits (trim clamp) so the audio and
 * video tracks are removed together without muting or blurring. Replacement
 * operations substitute a different media source.
 */
@androidx.media3.common.util.UnstableApi
class ExportRenderer(private val context: Context) {

    data class RenderResult(
        val outputPath: String,
        val outputDurationUs: Long,
        val sizeBytes: Long,
    )

    suspend fun render(
        request: RenderRequest,
        onProgress: suspend (Float) -> Unit,
    ): RenderResult = withContext(Dispatchers.Main) {
        val outputFile = File(request.outputPath)
        // Never overwrite source.
        if (outputFile.absolutePath == request.sourceUri) {
            throw ClearCutError.RenderError("Refusing to overwrite the source file.")
        }

        if (EdlEvaluator.hasOverlappingCuts(request.edl)) {
            throw ClearCutError.RenderError("The edit list contains overlapping cuts. Adjust the ranges and retry.")
        }
        val statuses = EdlEvaluator.validate(request.edl)
        if (statuses.any { it == ValidationStatus.INVALID }) {
            throw ClearCutError.RenderError("The edit list contains an invalid operation (out-of-range timestamps).")
        }

        val segments = EdlEvaluator.computeSegments(request.edl)
        if (segments.isEmpty()) {
            throw ClearCutError.RenderError("The edit list would produce an empty output.")
        }

        // Each surviving source segment becomes a clipped media item joined in order.
        val mediaItems = segments.map { seg ->
            val clipped = MediaItem.Builder()
                .setUri(uriFromString(request.sourceUri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(seg.sourceRange.startUs / 1000L)
                        .setEndPositionMs(seg.sourceRange.endUs / 1000L)
                        .build()
                )
                .build()
            EditedMediaItem.Builder(clipped)
                .setRemoveAudio(false)
                .setRemoveVideo(false)
                .build()
        }

        // REPLACE operations substitute the marked range with replacement media.
        // Implemented as additional edited items appended after surviving segments.
        val replacementItems = request.edl.operations
            .filter { it.operationType == EditOperationType.REPLACE && it.replacementSourceUri != null }
            .map { op ->
                EditedMediaItem.Builder(MediaItem.fromUri(uriFromString(op.replacementSourceUri!!)))
                    .setRemoveAudio(false)
                    .setRemoveVideo(false)
                    .build()
            }

        val allItems = mediaItems + replacementItems
        val sequence = EditedMediaItemSequence(allItems)
        val composition = Composition.Builder(sequence).build()

        checkAvailableSpace(request)

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(request.requestedVideoMime ?: MIME_TYPE_AVC)
            .setAudioMimeType(request.requestedAudioMime ?: MIME_TYPE_AAC)
            .build()

        awaitCompletion(transformer, composition, outputFile, onProgress)
    }

    private suspend fun awaitCompletion(
        transformer: Transformer,
        composition: Composition,
        outputFile: File,
        onProgress: suspend (Float) -> Unit,
    ): RenderResult = suspendCancellableCoroutine { cont ->
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                val duration = exportResult.durationMs * 1000
                cont.resume(
                    RenderResult(
                        outputPath = outputFile.absolutePath,
                        outputDurationUs = duration,
                        sizeBytes = outputFile.length(),
                    )
                )
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                cont.resumeWithException(
                    when (exportException) {
                        is ExportException -> ClearCutError.RenderError(
                            "Export failed: ${exportException.errorCodeName ?: "codec error"}. This can happen when the device encoder or source codecs are incompatible.",
                            exportException,
                        )
                        else -> ClearCutError.RenderError("Export failed: ${exportException.message ?: "unknown error"}", exportException)
                    }
                )
            }
        }
        transformer.addListener(listener)
        transformer.start(composition, outputFile.path)
        cont.invokeOnCancellation {
            runCatching { transformer.cancel() }
        }
    }

    private fun checkAvailableSpace(request: RenderRequest) {
        val stat = android.os.StatFs(request.outputPath.substringBeforeLast('/'))
        val available = stat.availableBytes
        if (available < request.expectedSizeBytes * 2 && request.expectedSizeBytes > 0) {
            throw ClearCutError.StorageError(
                "Insufficient storage for the expected export size (${available / 1024 / 1024} MB available)."
            )
        }
    }

    private fun uriFromString(s: String): Uri = Uri.parse(s)

    companion object {
        const val MIME_TYPE_AVC = "video/avc"
        const val MIME_TYPE_AAC = "audio/mp4a-latm"
    }
}