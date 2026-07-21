package no.mlz.shortener.routes

import no.mlz.shortener.AppComponents
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/** Request body exceeded the configured cap (§2.1). Mapped to 413. */
class PayloadTooLargeException : RuntimeException()

private val json = Json { ignoreUnknownKeys = true }

fun Application.linkRoutes(components: AppComponents) {
    val service = components.linkService

    routing {
        post("/links") {
            if (!allow(components, isCreate = true)) return@post
            val request = receiveCreateRequest(components.maxBodyBytes)
            val result = service.createLink(request.targetUrl, request.expiresAt)
            call.respond(
                HttpStatusCode.Created,
                CreateLinkResponse(result.code, result.shortUrl, result.ownerToken),
            )
        }

        get("/links/{code}/stats") {
            val code = call.parameters["code"].orEmpty()
            val stats = service.stats(code, bearerToken())
            call.respond(
                StatsResponse(
                    totalClicks = stats.totalClicks,
                    last7Days = stats.last7Days.map { DailyCountDto(it.date.toString(), it.count) },
                ),
            )
        }

        delete("/links/{code}") {
            val code = call.parameters["code"].orEmpty()
            service.delete(code, bearerToken())
            call.respond(HttpStatusCode.NoContent)
        }

        get("/{code}") {
            if (!allow(components, isCreate = false)) return@get
            val code = call.parameters["code"].orEmpty()
            val target = service.resolveAndTrack(code)
            call.respondRedirect(target, permanent = false)
        }
    }
}

/** Applies the appropriate token bucket; on denial writes 429 + Retry-After and returns false. */
private suspend fun RoutingContext.allow(components: AppComponents, isCreate: Boolean): Boolean {
    val limiter = if (isCreate) components.createLimiter else components.redirectLimiter
    val decision = limiter.check(call.request.origin.remoteHost)
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
    if (!header.startsWith("Bearer ")) return null
    return header.removePrefix("Bearer ").trim().takeIf { it.isNotEmpty() }
}

private suspend fun RoutingContext.receiveCreateRequest(maxBytes: Long): CreateLinkRequest {
    val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declaredLength != null && declaredLength > maxBytes) throw PayloadTooLargeException()

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
