package no.mlz.shortener.security

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.response.header

// A JSON API serving no HTML, so the default CSP is maximally restrictive. The self-hosted Swagger
// UI is the one exception: its assets are same-origin, so the docs CSP allows 'self' only (no CDN).
// 'unsafe-inline' remains for Swagger UI's bootstrap script and the styles it injects at runtime.
private const val API_CSP = "default-src 'none'"
private const val DOCS_CSP =
    "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data:; font-src 'self' data:; connect-src 'self'"

val SecurityHeaders = createApplicationPlugin(name = "SecurityHeaders") {
    onCall { call ->
        val isDocs = call.request.path().startsWith("/swagger")
        with(call.response) {
            header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
            header("X-Content-Type-Options", "nosniff")
            header("X-Frame-Options", "DENY")
            header("Referrer-Policy", "no-referrer")
            header("Content-Security-Policy", if (isDocs) DOCS_CSP else API_CSP)
        }
    }
}

fun Application.installSecurityHeaders() = install(SecurityHeaders)
