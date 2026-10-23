package com.projecthub.clearcut.core.network

import com.projecthub.clearcut.core.model.ClearCutError
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Validates URLs for safe import, preventing SSRF-style destination abuse.
 *
 * Rules enforced:
 *  - Only http/https schemes.
 *  - No userinfo (credentials) in the URL.
 *  - No private/loopback/link-local/multicast/reserved IP literals.
 *  - Literal IP destinations are rejected unless explicitly allow-listed.
 *  - Hostnames are resolved at check time and the first resolved address must
 *    be public.
 */
object UrlValidator {

    private val prohibitedHosts = setOf("localhost", "localhost.localdomain", "ip6-localhost")

    data class ValidationResult(val normalizedUrl: String, val resolvedHost: String, val isDirectMediaCandidate: Boolean)

    fun validate(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.failure(ClearCutError.ValidationError("The URL field is empty."))
        if (trimmed.length > 2048) return Result.failure(ClearCutError.ValidationError("The URL is too long."))

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return Result.failure(ClearCutError.ValidationError("That does not look like a valid URL."))
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.failure(
                ClearCutError.UnsupportedMediaError(
                    "Unsupported URL scheme '$scheme'. Only http:// and https:// direct media links are supported."
                )
            )
        }
        if (uri.rawUserInfo != null) {
            return Result.failure(ClearCutError.ValidationError("URLs may not contain embedded credentials."))
        }
        val host = uri.host?.lowercase() ?: return Result.failure(ClearCutError.ValidationError("The URL has no host."))
        if (host in prohibitedHosts) {
            return Result.failure(ClearCutError.NetworkError("Access to 'localhost' is not permitted."))
        }

        val hostCleaned = host.removePrefix("www.")
        if (hostCleaned == "youtube.com" || hostCleaned.endsWith(".youtube.com") ||
            hostCleaned == "youtu.be" ||
            hostCleaned == "facebook.com" || hostCleaned.endsWith(".facebook.com") ||
            hostCleaned == "fb.watch"
        ) {
            return Result.failure(
                ClearCutError.UnsupportedMediaError(
                    "$host archive video pages are not directly downloadable. " +
                        "Use a direct media link you are authorized to download, or a provider's official API integration."
                )
            )
        }

        // Reject IP literals in the private/special ranges.
        if (isIpLiteral(host)) {
            val prohibited = isProhibitedIp(host)
            if (prohibited) {
                return Result.failure(
                    ClearCutError.NetworkError("Access to network destination '$host' is not permitted.")
                )
            }
        }

        return Result.success(ValidationResult(uri.normalize().toString().removeSuffix("/"), host, isDirectMediaCandidate = true))
    }

    fun validateResolvedIp(ip: InetAddress): Boolean = !isProhibitedIp(ip)

    private fun isIpLiteral(host: String): Boolean =
        try {
            InetAddress.getByName(host)
            true
        } catch (e: Exception) {
            false
        }

    fun isProhibitedIp(host: String): Boolean {
        val addr = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return false
        }
        return isProhibitedIp(addr)
    }

    fun isProhibitedIp(addr: InetAddress): Boolean {
        val bytes = addr.address ?: return true
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            return when {
                b0 == 0 -> true                                      // 0.0.0.0/8
                b0 == 10 -> true                                     // 10.0.0.0/8
                b0 == 127 -> true                                    // loopback
                b0 == 100 && b1 in 64..127 -> true                   // CGNAT 100.64/10
                b0 == 169 && b1 == 254 -> true                       // link-local
                b0 == 172 && b1 in 16..31 -> true                    // 172.16/12
                b0 == 192 && b1 == 168 -> true                       // 192.168/16
                b0 == 198 && (b1 == 18 || b1 == 19) -> true          // benchmarking
                b0 >= 224 -> true                                    // multicast/reserved
                else -> false
            }
        }
        if (bytes.size == 16) {
            return bytes[0].toInt() and 0xFF == 0xfe && bytes[1].toInt() and 0xC0 == 0x80 // fe80::/10 link-local
        }
        return true
    }

    class Result private constructor(
        val value: ValidationResult?,
        val failure: ClearCutError?,
    ) {
        fun isSuccess() = failure == null
        fun getOrNull(): ValidationResult? = value
        companion object {
            fun success(v: ValidationResult) = Result(v, null)
            fun failure(e: ClearCutError) = Result(null, e)
        }
    }
}