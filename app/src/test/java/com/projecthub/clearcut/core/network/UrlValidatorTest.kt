package com.projecthub.clearcut.core.network

import com.google.common.truth.Truth.assertThat
import com.projecthub.clearcut.core.model.ClearCutError
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun `accepts https direct media url`() {
        val result = UrlValidator.validate("https://cdn.example.com/video.mp4")
        assertThat(result.isSuccess()).isTrue()
        assertThat(result.getOrNull()?.normalizedUrl).isNotNull()
        assertThat(result.getOrNull()?.isDirectMediaCandidate).isTrue()
    }

    @Test
    fun `rejects empty input`() {
        val result = UrlValidator.validate("   ")
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.failure).isInstanceOf(ClearCutError.ValidationError::class.java)
    }

    @Test
    fun `rejects unsupported scheme`() {
        val result = UrlValidator.validate("ftp://example.com/file.mp4")
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.failure).isInstanceOf(ClearCutError.UnsupportedMediaError::class.java)
    }

    @Test
    fun `rejects javascript scheme`() {
        val result = UrlValidator.validate("javascript:alert(1)")
        assertThat(result.isSuccess()).isFalse()
    }

    @Test
    fun `rejects embedded credentials`() {
        val result = UrlValidator.validate("https://user:pass@example.com/file.mp4")
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.failure).isInstanceOf(ClearCutError.ValidationError::class.java)
    }

    @Test
    fun `rejects localhost`() {
        val result = UrlValidator.validate("http://localhost/video.mp4")
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.failure).isInstanceOf(ClearCutError.NetworkError::class.java)
    }

    @Test
    fun `rejects loopback ip`() {
        val result = UrlValidator.validate("http://127.0.0.1/video.mp4")
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.failure).isInstanceOf(ClearCutError.NetworkError::class.java)
    }

    @Test
    fun `rejects private rfc1918 ip`() {
        for (host in listOf("10.0.0.5", "192.168.1.10", "172.16.0.1", "169.254.1.1")) {
            val result = UrlValidator.validate("http://$host/x.mp4")
            assertThat(result.isSuccess()).isFalse()
            assertThat(result.failure).isInstanceOf(ClearCutError.NetworkError::class.java)
        }
    }

    @Test
    fun `rejects multicast and reserved ranges`() {
        for (host in listOf("224.0.0.1", "255.255.255.255", "0.0.0.0")) {
            val result = UrlValidator.validate("http://$host/x.mp4")
            assertThat(result.isSuccess()).isFalse()
        }
    }

    @Test
    fun `rejects ipv6 link-local`() {
        val result = UrlValidator.validate("http://[fe80::1]/x.mp4")
        assertThat(result.isSuccess()).isFalse()
    }

    @Test
    fun `rejects platform video pages that are not direct media`() {
        val result = UrlValidator.validate("https://www.youtube.com/watch?v=abc123")
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.failure).isInstanceOf(ClearCutError.UnsupportedMediaError::class.java)
    }

    @Test
    fun `rejects facebook pages`() {
        val result = UrlValidator.validate("https://www.facebook.com/watch?v=123")
        assertThat(result.isSuccess()).isFalse()
    }

    @Test
    fun `isProhibitedIp guards reserved ipv4`() {
        assertThat(UrlValidator.isProhibitedIp("1.2.3.4")).isFalse()
        assertThat(UrlValidator.isProhibitedIp("8.8.8.8")).isFalse()
        assertThat(UrlValidator.isProhibitedIp("10.0.0.1")).isTrue()
        assertThat(UrlValidator.isProhibitedIp("127.0.0.1")).isTrue()
        assertThat(UrlValidator.isProhibitedIp("192.168.1.1")).isTrue()
        assertThat(UrlValidator.isProhibitedIp("172.31.255.255")).isTrue()
        assertThat(UrlValidator.isProhibitedIp("169.254.100.1")).isTrue()
    }
}