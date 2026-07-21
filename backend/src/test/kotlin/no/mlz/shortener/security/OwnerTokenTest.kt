package no.mlz.shortener.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OwnerTokenTest {

    @Test
    fun `generates distinct high-entropy tokens`() {
        val tokens = List(1000) { OwnerToken.generate() }
        assertEquals(1000, tokens.toSet().size)
        // 32 bytes base64url without padding -> 43 chars
        assertTrue(tokens.all { it.length >= 43 })
    }

    @Test
    fun `hash is 64 hex chars and stable`() {
        val hash = OwnerToken.hash("some-token")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
        assertEquals(hash, OwnerToken.hash("some-token"))
    }

    @Test
    fun `hash differs from the raw token`() {
        val token = OwnerToken.generate()
        assertNotEquals(token, OwnerToken.hash(token))
    }

    @Test
    fun `matches only the originating token`() {
        val token = OwnerToken.generate()
        val hash = OwnerToken.hash(token)
        assertTrue(OwnerToken.matches(token, hash))
        assertFalse(OwnerToken.matches(token + "x", hash))
    }
}
