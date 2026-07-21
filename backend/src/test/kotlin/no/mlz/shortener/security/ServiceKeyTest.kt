package no.mlz.shortener.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServiceKeyTest {

    @Test
    fun `matches only the exact key`() {
        assertTrue(ServiceKey.matches("correct-horse", "correct-horse"))
        assertFalse(ServiceKey.matches("correct-horse ", "correct-horse"))
        assertFalse(ServiceKey.matches("wrong", "correct-horse"))
        assertFalse(ServiceKey.matches("", "correct-horse"))
        assertFalse(ServiceKey.matches(null, "correct-horse"))
    }
}
