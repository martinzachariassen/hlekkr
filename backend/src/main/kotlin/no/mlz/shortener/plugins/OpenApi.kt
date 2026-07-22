package no.mlz.shortener.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

private const val SPEC_RESOURCE = "openapi/documentation.yaml"

// Must match the swagger-ui webjar version in gradle/libs.versions.toml — the webjar embeds it in
// the resource path. Served same-origin (no CDN) so the docs CSP allows no third-party host.
private const val SWAGGER_UI_VERSION = "5.32.8"
private const val SWAGGER_ASSETS = "META-INF/resources/webjars/swagger-ui/$SWAGGER_UI_VERSION"

// Bare filename with a js/css extension — no slashes, no '..' — so a request can't traverse out
// of the vendored asset directory.
private val ASSET_NAME = Regex("""[a-z0-9-]+\.(js|css)""")

private val SWAGGER_PAGE = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <title>API reference — Hlekkr</title>
      <link rel="stylesheet" href="/swagger/dist/swagger-ui.css">
    </head>
    <body>
      <div id="swagger-ui"></div>
      <script src="/swagger/dist/swagger-ui-bundle.js"></script>
      <script src="/swagger/dist/swagger-ui-standalone-preset.js"></script>
      <script>
        window.ui = SwaggerUIBundle({
          url: '/openapi.yaml',
          dom_id: '#swagger-ui',
          presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
          layout: 'StandaloneLayout'
        });
      </script>
    </body>
    </html>
""".trimIndent()

// Assets live under /swagger/dist/ so Caddy's `/swagger/*` matcher proxies them in prod.
fun Application.configureOpenApi() {
    val spec = environment.classLoader.getResource(SPEC_RESOURCE)?.readText()
    val loader = environment.classLoader
    routing {
        get("/openapi.yaml") {
            if (spec == null) {
                call.respondText("OpenAPI spec not found", status = HttpStatusCode.NotFound)
            } else {
                call.respondText(spec, ContentType("application", "yaml"))
            }
        }
        get("/swagger") { call.respondText(SWAGGER_PAGE, ContentType.Text.Html) }
        get("/swagger/dist/{file}") {
            val file = call.parameters["file"].orEmpty()
            val bytes = if (ASSET_NAME.matches(file)) loader.getResourceAsStream("$SWAGGER_ASSETS/$file")?.readBytes() else null
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                val type = if (file.endsWith(".css")) ContentType.Text.CSS else ContentType.Application.JavaScript
                call.respondBytes(bytes, type)
            }
        }
    }
}
