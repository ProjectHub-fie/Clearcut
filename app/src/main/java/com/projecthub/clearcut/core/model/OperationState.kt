package com.projecthub.clearcut.core.model

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Explicit state machine for every asynchronous, user-visible workflow.
 *
 * Never invent a percentage: [InProgress.progress] is `null` except when the
 * underlying operation exposes a meaningful 0f..1f progress value.
 */
sealed interface OperationState<out T> {
    object Idle : OperationState<Nothing>
    data class Validating(val detail: String) : OperationState<Nothing>
    data class InProgress<out T>(
        val phase: String,
        val progress: Float? = null,
        val elapsedMs: Long = 0L,
        val estimatedRemainingMs: Long? = null,
    ) : OperationState<T>

    data class Waiting<out T>(val detail: String, val elapsedMs: Long = 0L) : OperationState<T>
    data class Completed<out T>(val value: T) : OperationState<T>
    data class CompletedWithWarnings<out T>(val value: T, val warnings: List<String>) : OperationState<T>
    data class Cancelled(val phaseAtCancel: String) : OperationState<Nothing>
    data class Failed(val error: ClearCutError) : OperationState<Nothing>

    val isActive: Boolean
        get() = this is Validating || this is InProgress || this is Waiting

    val isFinished: Boolean
        get() = this is Completed || this is CompletedWithWarnings || this is Cancelled || this is Failed

    val isError: Boolean
        get() = this is Failed

    fun <R> map(transform: (T) -> R): OperationState<R> = when (this) {
        is Idle -> Idle
        is Validating -> this
        is InProgress -> InProgress(phase, progress, elapsedMs, estimatedRemainingMs)
        is Waiting -> Waiting(detail, elapsedMs)
        is Completed -> Completed(transform(value))
        is CompletedWithWarnings -> CompletedWithWarnings(transform(value), warnings)
        is Cancelled -> this
        is Failed -> this
    }
}

fun <T> MutableStateFlow<OperationState<T>>.beginIfFree(validatingDetail: String): Boolean {
    if (value.isActive || value.isFinished && value !is OperationState.Completed) return false
    value = OperationState.Validating(validatingDetail)
    return true
}
