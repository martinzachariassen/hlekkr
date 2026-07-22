package no.mlz.shortener.security

import java.io.File

// Denylist matched by domain suffix: "evil.com" blocks it and every subdomain. Lookup walks label
// suffixes, so cost is O(dots in host) regardless of list size. Never resolves DNS.
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

        // Tolerates hosts-file lines ("0.0.0.0 x.com" -> last field), '#' comments, and blanks so
        // published feeds work as-is. An unreadable file contributes nothing.
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
