package no.mlz.shortener.security

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.response.header

// A JSON API serving no HTML, so the default CSP is maximally restrictive. The Swagger UI is the
// one exception: its assets load from the unpkg CDN, so those paths get a CSP scoped to exactly that.
private const val API_CSP = "default-src 'none'"
private const val SWAGGER_CDN = "https://unpkg.com"
private const val DOCS_CSP =
    "default-src 'none'; script-src 'self' 'unsafe-inline' $SWAGGER_CDN; " +
        "style-src 'self' 'unsafe-inline' $SWAGGER_CDN; img-src 'self' data: $SWAGGER_CDN; " +
        "font-src 'self' data: $SWAGGER_CDN; connect-src 'self'"

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
