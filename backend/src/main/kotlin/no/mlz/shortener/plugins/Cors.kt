package no.mlz.shortener.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

// Explicit allow-list only, never anyHost(). Credentials stay off: owner tokens ride the
// Authorization header, not cookies, so there's no ambient credential to protect. Empty list
// disables cross-origin access entirely.
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
