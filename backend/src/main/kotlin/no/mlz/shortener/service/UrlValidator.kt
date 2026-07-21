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

        val host = uri.host?.trim('[', ']')?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
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
        // IPv6 literals: getByName parses the numeric form without a DNS lookup.
        if (host.contains(':')) return runCatching { InetAddress.getByName(host) }.getOrNull()
        return parseIpv4(host)
    }

    // Canonicalize an IPv4 literal the way a browser/inet_aton would — so alternate encodings
    // (decimal 2130706433, hex 0x7f000001, octal 0177.0.0.1, short-form 10.1) all resolve to the
    // address a client would actually reach, then get range-checked. Java's own getByName is NOT
    // used here: it mis-reads a leading-zero part as decimal, not octal. Returns null for a genuine
    // hostname (a non-numeric label), so no DNS is ever performed.
    private fun parseIpv4(host: String): InetAddress? {
        val parts = host.split('.')
        if (parts.size > 4) return null
        val values = parts.map { parseIpv4Part(it) ?: return null }
        val maxPerPart = when (parts.size) {
            1 -> longArrayOf(0xFFFFFFFFL)
            2 -> longArrayOf(0xFF, 0xFFFFFF)
            3 -> longArrayOf(0xFF, 0xFF, 0xFFFF)
            else -> longArrayOf(0xFF, 0xFF, 0xFF, 0xFF)
        }
        var addr = 0L
        for (i in values.indices) {
            if (values[i] > maxPerPart[i]) return null
            // Leading parts are one byte each; the final part fills the remaining low bytes.
            val shift = if (i == values.lastIndex) 0 else 8 * (3 - i)
            addr = addr or (values[i] shl shift)
        }
        val bytes = byteArrayOf(
            (addr shr 24 and 0xFF).toByte(),
            (addr shr 16 and 0xFF).toByte(),
            (addr shr 8 and 0xFF).toByte(),
            (addr and 0xFF).toByte(),
        )
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }

    private fun parseIpv4Part(part: String): Long? = try {
        when {
            part.startsWith("0x") -> part.substring(2).takeIf { it.isNotEmpty() }?.toLong(16)
            part.length > 1 && part[0] == '0' -> part.toLong(8)
            else -> part.toLong(10)
        }
    } catch (_: NumberFormatException) {
        null
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
        val bytes = address.address
        // IPv6 unique-local (fc00::/7) is not covered by isSiteLocalAddress.
        if (bytes.size == 16) return (bytes[0].toInt() and 0xFE) == 0xFC
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return (b0 == 100 && b1 in 64..127) ||        // 100.64.0.0/10 carrier-grade NAT
            bytes.all { (it.toInt() and 0xFF) == 255 } // 255.255.255.255 broadcast
    }

    companion object {
        const val MAX_LENGTH = 2048
        private val ALLOWED_SCHEMES = setOf("http", "https")
    }
}
