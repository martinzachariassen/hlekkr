package no.mlz.shortener.security

import java.io.File

// A denylist of target hosts the service refuses to shorten — adult, malware, phishing, or whatever
// the operator's policy forbids. Matching is by domain suffix: an entry "evil.com" blocks "evil.com"
// and every subdomain ("a.b.evil.com"). The set is held in memory and each check walks the host's
// label suffixes, so lookups are O(number of dots in the host) regardless of list size — a
// categorized feed with 100k+ domains adds negligible per-request cost.
//
// It ships EMPTY: with no list configured this is a no-op and behaviour is unchanged. Populate it
// from a categorized blocklist (see README) via BLOCKED_HOSTS (inline) or BLOCKED_HOSTS_FILE.
// DNS is never resolved here — like the rest of UrlValidator, this matches the literal host only.
class HostBlocklist private constructor(private val blocked: Set<String>) {

    val size: Int get() = blocked.size

    fun isBlocked(host: String): Boolean {
        if (blocked.isEmpty()) return false
        val h = host.lowercase().trim('.')
        var start = 0
        while (true) {
            if (h.substring(start) in blocked) return true
            val dot = h.indexOf('.', start)
            if (dot < 0) return false
            start = dot + 1
        }
    }

    companion object {
        fun empty() = HostBlocklist(emptySet())

        // Builds from an inline comma/newline-separated string plus an optional file with one host
        // per line. Blank lines and '#' comments are ignored, and hosts-file lines ("0.0.0.0 x.com")
        // are supported by taking the last field — so StevenBlack/hosts and UT1 domain lists both
        // work. An unreadable file contributes nothing rather than failing startup.
        fun from(inline: String, filePath: String?): HostBlocklist {
            val fileLines = filePath
                ?.let { runCatching { File(it).readLines() }.getOrDefault(emptyList()) }
                ?: emptyList()
            return of(inline.split(',', '\n') + fileLines)
        }

        fun of(entries: Iterable<String>): HostBlocklist =
            HostBlocklist(entries.mapNotNull(::normalize).toSet())

        private fun normalize(line: String): String? {
            val withoutComment = line.substringBefore('#').trim()
            if (withoutComment.isEmpty()) return null
            val host = withoutComment.split(Regex("\\s+")).last().lowercase().trim('.')
            return host.takeUnless { it.isEmpty() || it == "localhost" || it == "0.0.0.0" }
        }
    }
}
