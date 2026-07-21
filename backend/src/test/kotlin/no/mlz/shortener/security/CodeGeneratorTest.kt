package no.mlz.shortener.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeGeneratorTest {

    private val base62 = ('A'..'Z') + ('a'..'z') + ('0'..'9')

    @Test
    fun `produces codes of the configured length`() {
        val generator = CodeGenerator(length = 7)
        repeat(100) { assertEquals(7, generator.newCode().length) }
    }

    @Test
    fun `uses only base62 characters`() {
        val generator = CodeGenerator(length = 7)
        repeat(100) { code -> assertTrue(generator.newCode().all { it in base62 }) }
    }

    @Test
    fun `is overwhelmingly likely to produce distinct codes`() {
        val generator = CodeGenerator(length = 7)
        val codes = List(10_000) { generator.newCode() }
        // 62^7 space: collisions in 10k draws should be effectively nil.
        assertTrue(codes.toSet().size >= 9_999)
    }
}
