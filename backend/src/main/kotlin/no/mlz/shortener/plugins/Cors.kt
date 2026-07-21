package no.mlz.shortener.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

// Explicit allow-list only, never anyHost(). Credentials stay off: owner tokens ride the
// Authorization header, not cookies. Empty list disables cross-origin access entirely.
fun Application.configureCors(allowedOrigins: List<String>) {
    if (allowedOrigins.isEmpty()) return
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        // Pin each entry to its own scheme; allowHost otherwise permits both http and https,
        // silently widening an https-only entry to also accept the http origin.
        allowedOrigins.forEach { origin ->
            val scheme = origin.substringBefore("://", missingDelimiterValue = "https")
            allowHost(origin.substringAfter("://"), schemes = listOf(scheme))
        }
    }
}
