package com.projecthub.clearcut.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClearCutErrorTest {

    @Test
    fun `errors carry stable codes and human readable messages`() {
        assertThat(ClearCutError.ValidationError("bad").code).isEqualTo("VALIDATION_ERROR")
        assertThat(ClearCutError.UnsupportedMediaError("nope").code).isEqualTo("UNSUPPORTED_MEDIA")
        assertThat(ClearCutError.PermissionError("denied").code).isEqualTo("PERMISSION")
        assertThat(ClearCutError.NetworkError("offline").code).isEqualTo("NETWORK")
        assertThat(ClearCutError.StorageError("full").code).isEqualTo("STORAGE")
        assertThat(ClearCutError.DecodeError("bad").code).isEqualTo("DECODE")
        assertThat(ClearCutError.AnalysisError("boom").code).isEqualTo("ANALYSIS")
        assertThat(ClearCutError.RenderError("fail").code).isEqualTo("RENDER")
        assertThat(ClearCutError.CancelledOperation().code).isEqualTo("CANCELLED")
        assertThat(ClearCutError.CancelledOperation().recoverable).isTrue()
    }

    @Test
    fun `recovery metadata is exposed`() {
        val recoverable = ClearCutError.NetworkError("retry")
        assertThat(recoverable.recoverable).isTrue()
        assertThat(recoverable.message).contains("retry")
        // Non-recoverable classes carry the flag false.
        assertThat(ClearCutError.AnalysisError("boom").recoverable).isFalse()
        assertThat(ClearCutError.UnsupportedMediaError("nope").recoverable).isFalse()
    }

    @Test
    fun `fromThrowable preserves ClearCutError`() {
        val original = ClearCutError.DecodeError("input corrupted")
        assertThat(ClearCutError.fromThrowable(original, "scan")).isSameInstanceAs(original)
    }

    @Test
    fun `fromThrowable maps cancellation`() {
        val e = CancellationException("user pressed stop")
        val mapped = ClearCutError.fromThrowable(e, "download")
        assertThat(mapped).isInstanceOf(ClearCutError.CancelledOperation::class.java)
        assertThat(mapped.recoverable).isTrue()
    }

    @Test
    fun `fromThrowable maps unknown into AnalysisError`() {
        assertThat(ClearCutError.fromThrowable(RuntimeException("weird"), "scan"))
            .isInstanceOf(ClearCutError.AnalysisError::class.java)
    }

    @Test
    fun `fromThrowable maps filenotfound to storage`() {
        assertThat(ClearCutError.fromThrowable(java.io.FileNotFoundException("gone"), "import"))
            .isInstanceOf(ClearCutError.StorageError::class.java)
    }

    @Test
    fun `fromThrowable maps ios to network unless storage full`() {
        assertThat(ClearCutError.fromThrowable(java.io.IOException("connect failed"), "download"))
            .isInstanceOf(ClearCutError.NetworkError::class.java)
        assertThat(ClearCutError.fromThrowable(java.io.IOException("No space left on device"), "render"))
            .isInstanceOf(ClearCutError.StorageError::class.java)
    }
}