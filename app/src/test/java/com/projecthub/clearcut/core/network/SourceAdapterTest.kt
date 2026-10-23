package com.projecthub.clearcut.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourceAdapterTest {

    private val registry = SourceAdapterRegistry(listOf(DirectMediaAdapter()))

    @Test
    fun `direct adapter handles http and https`() = runTest {
        assertThat(registry.find("https://cdn.example.com/a.mp4")).isNotNull()
        assertThat(registry.find("http://cdn.example.com/a.mp4")).isNotNull()
        assertThat(registry.find("ftp://example.com/a.mp4")).isNull()
        assertThat(registry.find("https")).isNull()
    }

    @Test
    fun `direct adapter returns the same url passthrough`() = runTest {
        val direct = DirectMediaAdapter().resolveDirectUrl("https://cdn.example.com/a.mp4")
        assertThat(direct.url).isEqualTo("https://cdn.example.com/a.mp4")
    }

    @Test
    fun `first adapter in registry wins`() {
        val a = object : SourceAdapter {
            override val sourceId = "a"
            override val displayName = "A"
            override fun handles(url: String) = url.contains("example.com")
            override suspend fun resolveDirectUrl(url: String) = SourceAdapter.DirectMedia(url, null, null)
        }
        val reg = SourceAdapterRegistry(listOf(a, DirectMediaAdapter()))
        assertThat(reg.find("https://example.com/x.mp4")?.sourceId).isEqualTo("a")
    }
}