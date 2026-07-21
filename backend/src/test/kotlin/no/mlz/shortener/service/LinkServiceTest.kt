package no.mlz.shortener.service

import no.mlz.shortener.repository.DuplicateCodeException
import no.mlz.shortener.repository.LinkRecord
import no.mlz.shortener.repository.LinkRepository
import no.mlz.shortener.security.CodeGenerator
import no.mlz.shortener.security.OwnerToken
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class LinkServiceTest {

    private val repository = mockk<LinkRepository>()
    private val codeGenerator = mockk<CodeGenerator>()
    private val clickTracker = mockk<ClickTracker>(relaxed = true)
    private val baseUrl = "https://sho.rt"

    private fun service(maxAttempts: Int = 5) = LinkService(
        repository = repository,
        urlValidator = UrlValidator(baseUrl),
        codeGenerator = codeGenerator,
        clickTracker = clickTracker,
        baseUrl = baseUrl,
        maxCodeAttempts = maxAttempts,
    )

    private fun link(id: Long, code: String, hash: String) = LinkRecord(
        id = id, code = code, targetUrl = "https://example.com",
        ownerTokenHash = hash, createdAt = OffsetDateTime.now(), expiresAt = null,
    )

    @Test
    fun `createLink stores only the token hash and returns the raw token once`() {
        every { codeGenerator.newCode() } returns "abc1234"
        val hashSlot = slot<String>()
        every { repository.insert("abc1234", "https://example.com", capture(hashSlot), null) } returns 1L

        val result = service().createLink("https://example.com", null)

        assertEquals("abc1234", result.code)
        assertEquals("https://sho.rt/abc1234", result.shortUrl)
        assertTrue(result.ownerToken.isNotBlank())
        // The DB only ever sees the SHA-256 hash, never the raw token.
        assertNotEquals(result.ownerToken, hashSlot.captured)
        assertEquals(OwnerToken.hash(result.ownerToken), hashSlot.captured)
    }

    @Test
    fun `createLink retries with a fresh code on collision`() {
        every { codeGenerator.newCode() } returnsMany listOf("dupdup1", "unique1")
        every { repository.insert("dupdup1", any(), any(), any()) } throws DuplicateCodeException()
        every { repository.insert("unique1", any(), any(), any()) } returns 2L

        val result = service().createLink("https://example.com", null)

        assertEquals("unique1", result.code)
        verify(exactly = 1) { repository.insert("dupdup1", any(), any(), any()) }
    }

    @Test
    fun `createLink fails closed after exhausting collision retries`() {
        every { codeGenerator.newCode() } returns "collide"
        every { repository.insert(any(), any(), any(), any()) } throws DuplicateCodeException()

        assertThrows(CodeGenerationException::class.java) {
            service(maxAttempts = 3).createLink("https://example.com", null)
        }
        verify(exactly = 3) { repository.insert(any(), any(), any(), any()) }
    }

    @Test
    fun `createLink rejects a past expiresAt`() {
        assertThrows(InvalidRequestException::class.java) {
            service().createLink("https://example.com", "2000-01-01T00:00:00Z")
        }
    }

    @Test
    fun `resolveAndTrack returns the target and records a click`() {
        every { repository.findLive("abc1234") } returns link(7, "abc1234", "hash")

        val target = service().resolveAndTrack("abc1234")

        assertEquals("https://example.com", target)
        verify(exactly = 1) { clickTracker.record(7) }
    }

    @Test
    fun `resolveAndTrack throws not-found for an unknown code`() {
        every { repository.findLive("missing") } returns null
        assertThrows(LinkNotFoundException::class.java) { service().resolveAndTrack("missing") }
    }

    @Test
    fun `stats and delete return not-found when the owner token does not match`() {
        val correctToken = "secret-token"
        val stored = link(9, "abc1234", OwnerToken.hash(correctToken))
        every { repository.findLive("abc1234") } returns stored

        assertThrows(LinkNotFoundException::class.java) { service().stats("abc1234", "wrong") }
        assertThrows(LinkNotFoundException::class.java) { service().delete("abc1234", "wrong") }
        assertThrows(LinkNotFoundException::class.java) { service().stats("abc1234", null) }
    }

    @Test
    fun `delete soft-deletes when the owner token matches`() {
        val token = "secret-token"
        every { repository.findLive("abc1234") } returns link(9, "abc1234", OwnerToken.hash(token))
        every { repository.softDelete("abc1234") } returns true

        service().delete("abc1234", token)

        verify(exactly = 1) { repository.softDelete("abc1234") }
    }
}
