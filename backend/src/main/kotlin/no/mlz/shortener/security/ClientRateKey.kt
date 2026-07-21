package no.mlz.shortener.security

import java.net.InetAddress

// Rate-limit bucket key for a client address. An IPv6 client typically controls an entire /64, so
// keying on the full address would let it mint unlimited fresh buckets by rotating within its block;
// collapse IPv6 to its /64 prefix instead. IPv4 keys on the full address.
object ClientRateKey {

    fun of(remoteHost: String): String {
        val host = remoteHost.trim('[', ']')
        if (':' !in host) return remoteHost
        // A ':' means an IPv6 literal, so getByName parses it without a DNS lookup.
        val bytes = runCatching { InetAddress.getByName(host).address }.getOrNull()
        if (bytes == null || bytes.size != 16) return remoteHost
        for (i in 8 until 16) bytes[i] = 0
        return runCatching { InetAddress.getByAddress(bytes).hostAddress + "/64" }.getOrDefault(remoteHost)
    }
}
