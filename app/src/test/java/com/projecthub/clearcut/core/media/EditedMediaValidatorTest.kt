package com.projecthub.clearcut.core.media

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditedMediaValidatorTest {

    @Test
    fun `missing output file fails validation`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val result = EditedMediaValidator.validate(context, File("/nonexistent/out.mp4"), null)
        assertThat(result.validated).isFalse()
        assertThat(result.warnings).isNotEmpty()
    }

    @Test
    fun `tiny file fails validation`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val f = File.createTempFile("clearcut_invalid_", ".mp4")
        f.writeBytes(ByteArray(50))
        try {
            val result = EditedMediaValidator.validate(context, f, null)
            assertThat(result.validated).isFalse()
        } finally {
            f.delete()
        }
    }

    @Test
    fun `garbage file fails to reopen`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val f = File.createTempFile("clearcut_garbage_", ".mp4")
        f.writeBytes(ByteArray(4096) { it.toByte() })
        try {
            val result = EditedMediaValidator.validate(context, f, null)
            // Robolectric has no real MediaExtractor; a failure to decode must
            // never be reported as success.
            assertThat(result.validated).isFalse()
            assertThat(result.warnings).isNotEmpty()
        } finally {
            f.delete()
        }
    }
}