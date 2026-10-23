package com.projecthub.clearcut.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class OperationStateTest {

    @Test
    fun `active states are detected`() {
        assertThat(OperationState.Idle.isActive).isFalse()
        assertThat(OperationState.Validating("check").isActive).isTrue()
        assertThat(OperationState.InProgress<Unit>("render", 0.5f).isActive).isTrue()
        assertThat(OperationState.Waiting<Unit>("retry").isActive).isTrue()
        assertThat(OperationState.Completed(Unit).isActive).isFalse()
        assertThat(OperationState.Cancelled("render").isActive).isFalse()
        assertThat(OperationState.Failed(ClearCutError.ValidationError("x")).isActive).isFalse()
    }

    @Test
    fun `finished and error states`() {
        assertThat(OperationState.Completed(1).isFinished).isTrue()
        assertThat(OperationState.CompletedWithWarnings(1, listOf("w")).isFinished).isTrue()
        assertThat(OperationState.Cancelled("x").isFinished).isTrue()
        val failed = OperationState.Failed(ClearCutError.ValidationError("x"))
        assertThat(failed.isFinished).isTrue()
        assertThat(failed.isError).isTrue()
        assertThat(OperationState.InProgress<Unit>("x").isError).isFalse()
    }

    @Test
    fun `map transforms completed values but not errors`() {
        val completed: OperationState<Int> = OperationState.Completed(2)
        assertThat(completed.map { it * 3 }).isEqualTo(OperationState.Completed(6))
        val failed: OperationState<Int> = OperationState.Failed(ClearCutError.ValidationError("x"))
        assertThat(failed.map { it * 3 }).isEqualTo(failed)
        val canc: OperationState<Int> = OperationState.Cancelled("x")
        assertThat(canc.map { it * 3 }).isEqualTo(canc)
    }

    @Test
    fun `beginIfFree does not start when active`() {
        val flow = MutableStateFlow<OperationState<Unit>>(OperationState.InProgress("scan", 0.25f))
        assertThat(flow.beginIfFree("duplicate")).isFalse()
        assertThat(flow.value).isEqualTo(OperationState.InProgress<Unit>("scan", 0.25f))
    }

    @Test
    fun `beginIfFree starts from idle`() {
        val flow = MutableStateFlow<OperationState<Unit>>(OperationState.Idle)
        assertThat(flow.beginIfFree("start")).isTrue()
        assertThat(flow.value).isEqualTo(OperationState.Validating("start"))
    }

    @Test
    fun `beginIfFree instruction is safe after completed`() {
        val flow = MutableStateFlow<OperationState<Unit>>(OperationState.Completed(Unit))
        assertThat(flow.beginIfFree("redo")).isTrue()
    }
}