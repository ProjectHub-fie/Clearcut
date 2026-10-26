package com.projecthub.clearcut.core.updates

import com.google.common.truth.Truth.assertThat
import com.projecthub.clearcut.core.model.ClearCutError
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {

    private lateinit var server: MockWebServer
    private lateinit var checker: UpdateChecker

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        checker = UpdateChecker(OkHttpClient.Builder().build(), server.url("/releases/latest").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun releaseBody(
        tag: String = "v1.1.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        apkName: String? = "clearcut-v1_1_0-full.apk",
    ): String = buildString {
        append("""{"tag_name":"$tag","name":"ClearCut $tag","draft":$draft,"prerelease":$prerelease,"assets":[""")
        if (apkName != null) {
            append("""{"name":"$apkName","browser_download_url":"https://example.com/$apkName","size":12345}""")
        }
        append("]}")
    }

    @Test
    fun `returns update when release is newer`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(releaseBody("v1.1.0")))
        val release = checker.checkForUpdate("1.0.0")
        assertThat(release).isNotNull()
        assertThat(release!!.semver).isEqualTo("1.1.0")
        assertThat(release.assets.single().name).isEqualTo("clearcut-v1_1_0-full.apk")
    }

    @Test
    fun `returns null when release is not newer`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(releaseBody("v1.0.0")))
        assertThat(checker.checkForUpdate("1.0.0")).isNull()

        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(releaseBody("v0.9.0")))
        assertThat(checker.checkForUpdate("1.0.0")).isNull()
    }

    @Test
    fun `ignores prereleases and drafts`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(releaseBody("v2.0.0", prerelease = true)))
        assertThat(checker.checkForUpdate("1.0.0")).isNull()

        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(releaseBody("v2.0.0", draft = true)))
        assertThat(checker.checkForUpdate("1.0.0")).isNull()
    }

    @Test
    fun `patch and minor version diffs are compared correctly`() {
        assertThat(VersionCompare.isNewer("1.1.1", "1.1.0")).isTrue()
        assertThat(VersionCompare.isNewer("1.1.0", "1.1.1")).isFalse()
        assertThat(VersionCompare.isNewer("1.2.0", "1.1.9")).isTrue()
        assertThat(VersionCompare.isNewer("2.0.0", "1.99.99")).isTrue()
        assertThat(VersionCompare.isNewer("1.1.0", "1.2")).isFalse()
        assertThat(VersionCompare.isNewer("1.1", "1.1.0")).isFalse()
        assertThat(VersionCompare.isNewer("1.1.0", "1.1.0")).isFalse()
        assertThat(VersionCompare.isNewer("v1.2.0", "1.1.0")).isTrue()
    }

    @Test
    fun `missing github release returns no update`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(checker.checkForUpdate("1.0.0")).isNull()
    }

    @Test
    fun `only signed release apks are installable update candidates`() {
        fun asset(name: String) = ReleaseInfo.Asset(name, "https://example.com/$name", sizeBytes = 100)

        assertThat(asset("clearcut-release.apk").isInstallable).isTrue()
        assertThat(asset("clearcut-v1.1.0-full.apk").isInstallable).isTrue()
        assertThat(asset("app-release.apk").isInstallable).isTrue()
        assertThat(asset("app-debug.apk").isInstallable).isFalse()
        assertThat(asset("app-release-unsigned.apk").isInstallable).isFalse()
        assertThat(asset("notes.txt").isInstallable).isFalse()
    }

    @Test
    fun `server error surfaces as ClearCutError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val thrown = runCatching { checker.checkForUpdate("1.0.0") }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(ClearCutError.HttpError::class.java)
        assertThat((thrown as ClearCutError).code).contains("500")
    }
}