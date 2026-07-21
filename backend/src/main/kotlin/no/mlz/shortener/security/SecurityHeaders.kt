package no.mlz.shortener.security

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.response.header

/**
 * Sets hardening headers on every response (§2.6). This is a JSON API that serves no HTML,
 * so the CSP is maximally restrictive (`default-src 'none'`) and framing is denied outright.
 */
val SecurityHeaders = createApplicationPlugin(name = "SecurityHeaders") {
    onCall { call ->
        with(call.response) {
            header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
            header("X-Content-Type-Options", "nosniff")
            header("X-Frame-Options", "DENY")
            header("Referrer-Policy", "no-referrer")
            header("Content-Security-Policy", "default-src 'none'")
        }
    }
}

fun Application.installSecurityHeaders() = install(SecurityHeaders)
