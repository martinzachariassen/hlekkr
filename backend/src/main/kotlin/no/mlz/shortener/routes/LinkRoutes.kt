package no.mlz.shortener.routes

import no.mlz.shortener.AppComponents
import no.mlz.shortener.security.ClientRateKey
import no.mlz.shortener.security.ServiceKey
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream

class PayloadTooLargeException : RuntimeException()

private const val INTERNAL_KEY_HEADER = "X-Internal-Key"
private val json = Json { ignoreUnknownKeys = true }
private val log = LoggerFactory.getLogger("no.mlz.shortener.routes")

fun Application.linkRoutes(components: AppComponents) {
    val service = components.linkService

    routing {
        // Liveness stays dependency-free so a DB blip never triggers a restart; /ready is the
        // DB-backed readiness probe.
        get("/health") { call.respondText("OK") }

        get("/ready") {
            val correlationId = call.callId ?: "unknown"
            val ready = withContext(Dispatchers.IO) {
                runCatching { components.checkReadiness() }.getOrElse {
                    // Message only, no stack trace — a sustained outage would flood the log every poll.
                    log.warn("Readiness check failed: {} [{}]", it.message ?: it.javaClass.simpleName, correlationId)
                    false
                }
            }
            if (ready) {
                call.respondText("READY")
            } else {
                call.respondText("NOT READY", status = HttpStatusCode.ServiceUnavailable)
            }
        }

        post("/links") {
            if (!fromFrontend(components) || !allow(components, isCreate = true)) return@post
            val request = receiveCreateRequest(components.maxBodyBytes)
            val result = withContext(Dispatchers.IO) {
                service.createLink(request.targetUrl, request.expiresAt)
            }
            call.respond(
                HttpStatusCode.Created,
                CreateLinkResponse(result.code, result.shortUrl, result.ownerToken),
            )
        }

        get("/links/{code}/stats") {
            if (!fromFrontend(components) || !allow(components, isCreate = false)) return@get
            val code = call.parameters["code"].orEmpty()
            val token = bearerToken()
            val stats = withContext(Dispatchers.IO) { service.stats(code, token) }
            call.respond(
                StatsResponse(
                    totalClicks = stats.totalClicks,
                    last7Days = stats.last7Days.map { DailyCountDto(it.date.toString(), it.count) },
                ),
            )
        }

        delete("/links/{code}") {
            if (!fromFrontend(components) || !allow(components, isCreate = false)) return@delete
            val code = call.parameters["code"].orEmpty()
            val token = bearerToken()
            withContext(Dispatchers.IO) { service.delete(code, token) }
            call.respond(HttpStatusCode.NoContent)
        }

        // The short link itself — the one route that must stay public.
        get("/{code}") {
            if (!allow(components, isCreate = false)) return@get
            val code = call.parameters["code"].orEmpty()
            val target = withContext(Dispatchers.IO) { service.resolveAndTrack(code) }
            call.respondRedirect(target, permanent = false)
        }
    }
}

// A missing/wrong internal key gets a generic 404 — never a 403 that would reveal the route
// exists. Open when no key is configured (local dev).
private suspend fun RoutingContext.fromFrontend(components: AppComponents): Boolean {
    val expected = components.internalApiKey ?: return true
    if (ServiceKey.matches(call.request.headers[INTERNAL_KEY_HEADER], expected)) return true
    call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found", call.callId ?: "unknown"))
    return false
}

private suspend fun RoutingContext.allow(components: AppComponents, isCreate: Boolean): Boolean {
    val limiter = if (isCreate) components.createLimiter else components.redirectLimiter
    val decision = limiter.check(ClientRateKey.of(call.request.origin.remoteHost))
    if (!decision.allowed) {
        call.response.header(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
        call.respond(
            HttpStatusCode.TooManyRequests,
            ErrorResponse("Rate limit exceeded", call.callId ?: "unknown"),
        )
        return false
    }
    return true
}

private fun RoutingContext.bearerToken(): String? {
    val header = call.request.headers[HttpHeaders.Authorization] ?: return null
    val prefix = "Bearer " // RFC 6750: the auth-scheme is case-insensitive.
    if (!header.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null
    return header.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}

private suspend fun RoutingContext.receiveCreateRequest(maxBytes: Long): CreateLinkRequest {
    val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declaredLength != null && declaredLength > maxBytes) throw PayloadTooLargeException()

    // Content-Length can be absent or a lying client can send more than it declared, so the
    // header check alone isn't enough — stream in chunks and re-check the running total too.
    val bytes = withContext(Dispatchers.IO) {
        call.receiveStream().use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                total += read
                if (total > maxBytes) throw PayloadTooLargeException()
                out.write(buf, 0, read)
            }
            out.toByteArray()
        }
    }
    return json.decodeFromString<CreateLinkRequest>(bytes.decodeToString())
}
