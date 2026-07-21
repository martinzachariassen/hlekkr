package no.mlz.shortener.service

/** Target URL failed validation (§2.1). [reason] is client-safe and mapped to 400. */
class InvalidTargetUrlException(val reason: String) : RuntimeException(reason)

/** A request field other than the URL was invalid (e.g. a past/garbled expiresAt). Mapped to 400. */
class InvalidRequestException(val reason: String) : RuntimeException(reason)

/**
 * Requested link is missing, expired, deleted, or the caller failed owner-token auth.
 * Deliberately undifferentiated so the public API can't be used to probe link existence (§2.3, §2.7).
 */
class LinkNotFoundException : RuntimeException()

/** Ran out of collision-retry attempts generating a unique short code (§2.2). */
class CodeGenerationException : RuntimeException("Could not allocate a unique short code")
