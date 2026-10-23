package com.projecthub.clearcut.core.network

import com.google.common.truth.Truth.assertThat
import com.projecthub.clearcut.core.model.ClearCutError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Transport-level downloader tests run against a local MockWebServer.
 * The SSRF validator and host preflight are bypassed here (loopback is
 * intentionally allowed for the test server); UrlValidatorTest covers the
 * SSRF policy separately.
 */
class OkHttpDownloaderTest {

    private lateinit var server: MockWebServer
    private val permissiveValidator: (String) -> UrlValidator.Result = { url ->
        UrlValidator.Result.success(UrlValidator.ValidationResult(url, "localhost", true))
    }
    private val noOpPreflight: (String) -> Unit = {}

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        // Clean any leftover temp files from interrupted downloads.
        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        tmpDir.listFiles { f -> f.name.startsWith("clearcut_dl_") }?.forEach { it.delete() }
    }

    private fun downloader(client: OkHttpClient = OkHttpClient.Builder().build()) =
        OkHttpDownloader(client, permissiveValidator, noOpPreflight)

    @Test
    fun `downloads media to a temp file`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody("fake-mp3-bytes")
        )
        val result = downloader().download(
            server.url("/song.mp3").toString(),
            onProgress = {},
            maxBytes = 10_000,
        )
        assertThat(result.contentType).contains("audio/mpeg")
        val f = File(result.filePath)
        assertThat(f.exists()).isTrue()
        assertThat(f.readBytes()).isEqualTo("fake-mp3-bytes".toByteArray())
        f.delete()
    }

    @Test
    fun `rejects html page instead of media`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>not a video</html>")
        )
        var thrown: Throwable? = null
        try {
            downloader().download(server.url("/watch").toString(), {}, 10_000)
        } catch (e: Throwable) {
            thrown = e
        }
        assertThat(thrown).isInstanceOf(ClearCutError.UnsupportedMediaError::class.java)
    }

    @Test
    fun `reports http 404 and 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))
        val notFound: Throwable? = runCatching {
            downloader().download(server.url("/x.mp3").toString(), {}, 10_000)
            null
        }.exceptionOrNull()
        assertThat(notFound).isInstanceOf(ClearCutError.NetworkError::class.java)
        assertThat((notFound as ClearCutError).message).contains("404")

        server.enqueue(MockResponse().setResponseCode(403).setBody("nope"))
        val forbidden: Throwable? = runCatching {
            downloader().download(server.url("/x.mp3").toString(), {}, 10_000)
            null
        }.exceptionOrNull()
        assertThat(forbidden).isInstanceOf(ClearCutError.NetworkError::class.java)
        assertThat((forbidden as ClearCutError).message).contains("403")
    }

    @Test
    fun `enforces download size limit`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Length", "5000")
                .setBody(Buffer().write(ByteArray(5000)))
        )
        var thrown: Throwable? = null
        try {
            downloader().download(server.url("/big.mp4").toString(), {}, 100)
        } catch (e: Throwable) {
            thrown = e
        }
        assertThat(thrown).isInstanceOf(ClearCutError.StorageError::class.java)
    }

    @Test
    fun `redirects are followed and reuse final async`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/final.mp4").toString())
                .setBody("")
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setBody("final-content")
        )
        val result = downloader().download(server.url("/start").toString(), {}, 10_000)
        assertThat(result.finalUrl).contains("/final.mp4")
        assertThat(File(result.filePath).readBytes()).isEqualTo("final-content".toByteArray())
        File(result.filePath).delete()
    }

    @Test
    fun `cancellation cleans up partial file`() = runTest {
        val big = ByteArray(4 * 1024 * 1024) { 1 }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setBody(Buffer().write(big))
        )
        val tmpBefore = File(System.getProperty("java.io.tmpdir"))
            .listFiles { f -> f.name.startsWith("clearcut_dl_") }?.size ?: 0

        var cancelled = false
        var intercepted: Throwable? = null
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                downloader().download(server.url("/huge.mp4").toString(), onProgress = {
                    // Cancel mid-stream.
                    throw kotlinx.coroutines.CancellationException("user cancelled")
                }, maxBytes = Long.MAX_VALUE)
            } catch (e: Throwable) {
                intercepted = e
                cancelled = e is kotlinx.coroutines.CancellationException
            }
        }
        job.join()
        assertThat(cancelled).isTrue()

        val tmpAfter = File(System.getProperty("java.io.tmpdir"))
            .listFiles { f -> f.name.startsWith("clearcut_dl_") }?.size ?: 0
        // Partial temp file must have been removed.
        assertThat(tmpAfter).isEqualTo(tmpBefore)
    }
}