package no.mlz.shortener.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Owner tokens are the whole authorization model (§2.3): no accounts, just a bearer secret
 * returned once at creation. We store only the SHA-256 hash — the raw token is never
 * persisted and must never be logged.
 */
object OwnerToken {

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    private const val TOKEN_BYTES = 32 // 256 bits, comfortably above the 128-bit floor

    /** Returns a fresh, URL-safe token for transport. Shown to the caller exactly once. */
    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    /** SHA-256 hex of the token — the only form that ever touches the database. */
    fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Constant-time comparison of a presented token against a stored hash. */
    fun matches(presentedToken: String, storedHash: String): Boolean =
        MessageDigest.isEqual(
            hash(presentedToken).toByteArray(Charsets.UTF_8),
            storedHash.toByteArray(Charsets.UTF_8),
        )
}
