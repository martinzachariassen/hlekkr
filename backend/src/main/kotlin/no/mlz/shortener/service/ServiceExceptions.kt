package no.mlz.shortener.service

// reason is client-safe; mapped to 400.
class InvalidTargetUrlException(val reason: String) : RuntimeException(reason)
class InvalidRequestException(val reason: String) : RuntimeException(reason)

// Undifferentiated on purpose (missing / expired / deleted / failed auth) so existence can't be probed.
class LinkNotFoundException : RuntimeException()

class CodeGenerationException : RuntimeException("Could not allocate a unique short code")
