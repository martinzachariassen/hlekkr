package no.mlz.shortener.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

/**
 * CORS from an explicit allow-list only (§2.6). Never `anyHost()`, and credentials are not
 * enabled — owner tokens travel in the Authorization header, not cookies, so there is no
 * ambient-credential surface to protect. An empty allow-list disables cross-origin access.
 */
fun Application.configureCors(allowedOrigins: List<String>) {
    if (allowedOrigins.isEmpty()) return
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowedOrigins.forEach { origin -> allowHost(origin.removePrefix("https://").removePrefix("http://")) }
    }
}
