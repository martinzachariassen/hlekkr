package no.mlz.shortener.service

import no.mlz.shortener.security.HostBlocklist
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

// SSRF-preventive: rejects loopback/private/link-local/metadata hosts so a later preview/fetch
// feature can't be tricked into hitting internal services. DNS is deliberately not resolved — only
// the literal host is checked; a future fetch must re-validate the resolved address, which can change.
class UrlValidator(baseUrl: String, private val blocklist: HostBlocklist = HostBlocklist.empty()) {

    private val selfHost: String = runCatching { URI(baseUrl).host }
        .getOrNull()
        ?.lowercase()
        ?: error("Configured app.baseUrl is not a valid URL: $baseUrl")

    fun validate(raw: String): String {
        if (raw.length > MAX_LENGTH) {
            throw InvalidTargetUrlException("URL exceeds maximum length of $MAX_LENGTH characters")
        }
        if (raw.isBlank()) {
            throw InvalidTargetUrlException("URL must not be blank")
        }

        val uri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            throw InvalidTargetUrlException("URL is not syntactically valid")
        }

        if (!uri.isAbsolute) {
            throw InvalidTargetUrlException("URL must be absolute (include a scheme)")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme !in ALLOWED_SCHEMES) {
            throw InvalidTargetUrlException("Only http and https URLs are allowed")
        }

        if (uri.userInfo != null) {
            throw InvalidTargetUrlException("URLs with embedded credentials are not allowed")
        }

        val host = uri.host?.trim('[', ']')?.lowercase()
            ?: throw InvalidTargetUrlException("URL must include a host")

        if (isBlockedHostname(host)) {
            throw InvalidTargetUrlException("URL targets a disallowed host")
        }

        asIpLiteral(host)?.let { ip ->
            if (isPrivateAddress(ip)) {
                throw InvalidTargetUrlException("URL targets a private, loopback, or link-local address")
            }
        }

        if (host == selfHost) {
            throw InvalidTargetUrlException("URL must not point back at this service")
        }

        if (blocklist.isBlocked(host)) {
            throw InvalidTargetUrlException("URL targets a blocked domain")
        }

        return raw
    }

    private fun isBlockedHostname(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost")

    private fun asIpLiteral(host: String): InetAddress? {
        val looksLikeIp = host.contains(':') || IPV4_LITERAL.matches(host)
        if (!looksLikeIp) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress ||
            address.isLinkLocalAddress ||      // 169.254/16 incl. cloud metadata, fe80::/10
            address.isSiteLocalAddress ||
            address.isAnyLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        // IPv6 unique-local (fc00::/7) is not covered by isSiteLocalAddress.
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }

    companion object {
        const val MAX_LENGTH = 2048
        private val ALLOWED_SCHEMES = setOf("http", "https")
        private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
