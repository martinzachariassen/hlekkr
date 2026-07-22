package no.mlz.shortener.service

import no.mlz.shortener.repository.DailyClickCount
import no.mlz.shortener.repository.DuplicateCodeException
import no.mlz.shortener.repository.LinkRepository
import no.mlz.shortener.security.CodeGenerator
import no.mlz.shortener.security.OwnerToken
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

data class CreateLinkResult(val code: String, val shortUrl: String, val ownerToken: String)

data class StatsResult(val totalClicks: Long, val last7Days: List<DailyClickCount>)

// Missing, expired, deleted, and failed owner-token auth all surface as the same exception so
// callers can't probe which links exist.
class LinkService(
    private val repository: LinkRepository,
    private val urlValidator: UrlValidator,
    private val codeGenerator: CodeGenerator,
    private val clickTracker: ClickTracker,
    private val baseUrl: String,
    private val maxCodeAttempts: Int,
) {

    fun createLink(rawTargetUrl: String, rawExpiresAt: String?): CreateLinkResult {
        val targetUrl = urlValidator.validate(rawTargetUrl)
        val expiresAt = parseExpiry(rawExpiresAt)

        val ownerToken = OwnerToken.generate()
        val ownerTokenHash = OwnerToken.hash(ownerToken)

        repeat(maxCodeAttempts) {
            val code = codeGenerator.newCode()
            try {
                repository.insert(code, targetUrl, ownerTokenHash, expiresAt)
                return CreateLinkResult(code, "$baseUrl/$code", ownerToken)
            } catch (_: DuplicateCodeException) {
                // Collision: retry with a fresh code, fail closed after maxCodeAttempts.
            }
        }
        throw CodeGenerationException()
    }

    fun resolveAndTrack(code: String): String {
        val link = repository.findLive(code) ?: throw LinkNotFoundException()
        clickTracker.record(link.id)
        return link.targetUrl
    }

    fun stats(code: String, presentedToken: String?): StatsResult {
        val link = authenticate(code, presentedToken)
        return StatsResult(
            totalClicks = repository.totalClicks(link.id),
            last7Days = repository.clicksLast7Days(link.id),
        )
    }

    fun delete(code: String, presentedToken: String?) {
        val link = authenticate(code, presentedToken)
        if (!repository.softDelete(link.code)) throw LinkNotFoundException()
    }

    private fun authenticate(code: String, presentedToken: String?) =
        repository.findLive(code)
            ?.takeIf { presentedToken != null && OwnerToken.matches(presentedToken, it.ownerTokenHash) }
            ?: throw LinkNotFoundException()

    private fun parseExpiry(raw: String?): OffsetDateTime? {
        if (raw.isNullOrBlank()) return null
        val instant = try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            throw InvalidRequestException("expiresAt must be an ISO-8601 instant (e.g. 2030-01-01T00:00:00Z)")
        }
        if (!instant.isAfter(Instant.now())) {
            throw InvalidRequestException("expiresAt must be in the future")
        }
        return instant.atOffset(ZoneOffset.UTC)
    }
}
