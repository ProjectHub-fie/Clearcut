package com.projecthub.clearcut.core.model

import kotlinx.coroutines.CancellationException as CoroutineCancellation

/**
 * Structured application-wide error model.
 *
 * Every error carries a stable, machine-readable [code], a safe human-readable
 * [message] (never a raw stack trace), optional [cause] for local logging, and a
 * [recoverable] flag describing whether retrying the same operation is meaningful.
 */
sealed class ClearCutError(
    val code: String,
    override val message: String,
    override val cause: Throwable? = null,
    val recoverable: Boolean = false,
) : Exception(message, cause) {

    override fun toString(): String = "ClearCutError(code=$code): $message"

    class ValidationError(msg: String, cause: Throwable? = null) :
        ClearCutError("VALIDATION_ERROR", msg, cause, recoverable = true)

    class UnsupportedMediaError(msg: String, cause: Throwable? = null) :
        ClearCutError("UNSUPPORTED_MEDIA", msg, cause, recoverable = false)

    class PermissionError(msg: String, cause: Throwable? = null) :
        ClearCutError("PERMISSION", msg, cause, recoverable = true)

    class NetworkError(msg: String, cause: Throwable? = null) :
        ClearCutError("NETWORK", msg, cause, recoverable = true)

    class HttpError(val status: Int, msg: String, cause: Throwable? = null) :
        ClearCutError("HTTP_$status", msg, cause, recoverable = status >= 500)

    class StorageError(msg: String, cause: Throwable? = null) :
        ClearCutError("STORAGE", msg, cause, recoverable = true)

    class DecodeError(msg: String, cause: Throwable? = null) :
        ClearCutError("DECODE", msg, cause, recoverable = true)

    class AnalysisError(msg: String, cause: Throwable? = null) :
        ClearCutError("ANALYSIS", msg, cause, recoverable = false)

    class RenderError(msg: String, cause: Throwable? = null) :
        ClearCutError("RENDER", msg, cause, recoverable = true)

    class CancelledOperation() :
        ClearCutError("CANCELLED", "The operation was cancelled.", null, recoverable = true)

    companion object {
        fun fromThrowable(t: Throwable, context: String = "operation"): ClearCutError {
            return when (t) {
                is ClearCutError -> t
                is CoroutineCancellation, is java.util.concurrent.CancellationException -> CancelledOperation()
                is android.system.ErrnoException -> StorageError("Storage unavailable ($context): ${t.errno}")
                is java.io.FileNotFoundException -> StorageError("File was not found ($context).")
                is java.io.IOException -> {
                    if (t.message?.contains("ENOSPC") == true || t.message?.contains("No space left") == true) {
                        StorageError("Insufficient storage space ($context).", t)
                    } else {
                        NetworkError("I/O error during $context: ${t.message ?: "unknown I/O error"}", t)
                    }
                }
                else -> AnalysisError("Unexpected failure during $context.", t)
            }
        }
    }
}
