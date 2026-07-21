package no.mlz.shortener.plugins

import no.mlz.shortener.routes.ErrorResponse
import no.mlz.shortener.routes.PayloadTooLargeException
import no.mlz.shortener.service.CodeGenerationException
import no.mlz.shortener.service.InvalidRequestException
import no.mlz.shortener.service.InvalidTargetUrlException
import no.mlz.shortener.service.LinkNotFoundException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

// The only place exceptions become responses: clients get a clean status, a safe message, and a
// correlation id; raw messages, stack traces, and SQL text stay in the server logs.
fun Application.configureStatusPages() {
    val log = LoggerFactory.getLogger("StatusPages")

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val correlationId = call.callId ?: "unknown"
            val (status, message) = classify(cause)

            if (status == HttpStatusCode.InternalServerError) {
                log.error("Unhandled error [{}]", correlationId, cause)
            } else {
                log.info("Request rejected [{}]: {} -> {}", correlationId, cause.javaClass.simpleName, status.value)
            }

            call.respond(status, ErrorResponse(error = message, correlationId = correlationId))
        }
    }
}

private fun classify(cause: Throwable): Pair<HttpStatusCode, String> = when (cause) {
    is InvalidTargetUrlException -> HttpStatusCode.BadRequest to cause.reason
    is InvalidRequestException -> HttpStatusCode.BadRequest to cause.reason
    is SerializationException,
    is BadRequestException,
    -> HttpStatusCode.BadRequest to "Malformed request body"
    is PayloadTooLargeException -> HttpStatusCode.PayloadTooLarge to "Request body too large"
    is LinkNotFoundException -> HttpStatusCode.NotFound to "Not found"
    is CodeGenerationException -> HttpStatusCode.ServiceUnavailable to "Temporarily unable to allocate a short code, please retry"
    else -> HttpStatusCode.InternalServerError to "Internal server error"
}
