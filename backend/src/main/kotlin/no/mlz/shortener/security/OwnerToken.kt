package no.mlz.shortener.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// The whole authorization model: no accounts, just a bearer secret returned once at creation.
// Only the SHA-256 hash is stored; the raw token is never persisted and must never be logged.
object OwnerToken {

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    private const val TOKEN_BYTES = 32 // 256 bits, well above the 128-bit floor

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // Constant-time so a wrong token can't be recovered by timing.
    fun matches(presentedToken: String, storedHash: String): Boolean =
        MessageDigest.isEqual(
            hash(presentedToken).toByteArray(Charsets.UTF_8),
            storedHash.toByteArray(Charsets.UTF_8),
        )
}
