package no.mlz.shortener.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

private const val SPEC_RESOURCE = "openapi/documentation.yaml"

// Hand-authored OpenAPI spec, served as raw YAML and via Swagger UI. Both are public: the spec
// is the contract, not data, and this is a portfolio API meant to be explorable.
fun Application.configureOpenApi() {
    val spec = environment.classLoader.getResource(SPEC_RESOURCE)?.readText()
    routing {
        get("/openapi.yaml") {
            if (spec == null) {
                call.respondText("OpenAPI spec not found", status = HttpStatusCode.NotFound)
            } else {
                call.respondText(spec, ContentType("application", "yaml"))
            }
        }
        swaggerUI(path = "swagger", swaggerFile = SPEC_RESOURCE)
    }
}
