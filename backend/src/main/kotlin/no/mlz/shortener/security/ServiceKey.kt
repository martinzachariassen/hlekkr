package no.mlz.shortener.security

import java.security.MessageDigest

// Service-to-service gate: only the frontend, which holds the configured key, may reach the
// management routes. Constant-time compare so a wrong key can't be recovered by timing.
object ServiceKey {

    fun matches(presented: String?, expected: String): Boolean {
        if (presented == null) return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }
}
