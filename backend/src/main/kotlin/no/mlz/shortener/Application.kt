package no.mlz.shortener

import no.mlz.shortener.config.AppConfig
import no.mlz.shortener.plugins.configureCors
import no.mlz.shortener.plugins.configureSerialization
import no.mlz.shortener.plugins.configureStatusPages
import no.mlz.shortener.repository.Database
import no.mlz.shortener.repository.LinkRepository
import no.mlz.shortener.routes.linkRoutes
import no.mlz.shortener.security.CodeGenerator
import no.mlz.shortener.security.TokenBucketRateLimiter
import no.mlz.shortener.security.installSecurityHeaders
import no.mlz.shortener.service.ClickTracker
import no.mlz.shortener.service.LinkService
import no.mlz.shortener.service.UrlValidator
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callid.CallId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** Everything the routing layer needs, assembled once at startup (or per test). */
class AppComponents(
    val linkService: LinkService,
    val createLimiter: TokenBucketRateLimiter,
    val redirectLimiter: TokenBucketRateLimiter,
    val maxBodyBytes: Long,
)

fun main() {
    val config = AppConfig.load()
    val database = Database(config.db)
    // In containers/production, migrations run as a separate privileged step (the Flyway service
    // in docker-compose, or a deploy job) under the admin role — the app role has no DDL rights
    // (§2.9). For local `./gradlew run` this defaults to true so a single command bootstraps the
    // schema. See README §Database hardening.
    if (System.getenv("RUN_MIGRATIONS_ON_STARTUP")?.toBoolean() != false) {
        database.migrate(
            migrationUser = System.getenv("DATABASE_MIGRATION_USER"),
            migrationPassword = System.getenv("DATABASE_MIGRATION_PASSWORD"),
        )
    }
    Runtime.getRuntime().addShutdownHook(Thread(database::close))

    embeddedServer(Netty, host = config.server.host, port = config.server.port) {
        module(config, LinkRepository(database))
    }.start(wait = true)
}

/**
 * Wires plugins, background workers, and routes. Takes a [LinkRepository] so tests can hand in
 * a Testcontainers-backed database while production hands in the pooled one from [main].
 */
fun Application.module(config: AppConfig, repository: LinkRepository) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clickTracker = ClickTracker(repository, appScope).also { it.start() }

    val components = buildComponents(config, repository, clickTracker)

    install(CallId) {
        header("X-Correlation-Id")
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
    }
    installSecurityHeaders()
    configureSerialization()
    configureStatusPages()
    configureCors(config.app.allowedOrigins)

    linkRoutes(components)

    monitor.subscribe(ApplicationStopping) {
        runBlocking { clickTracker.close() }
        appScope.cancel()
    }
}

private fun buildComponents(
    config: AppConfig,
    repository: LinkRepository,
    clickTracker: ClickTracker,
): AppComponents {
    val service = LinkService(
        repository = repository,
        urlValidator = UrlValidator(config.app.baseUrl),
        codeGenerator = CodeGenerator(config.app.code.length),
        clickTracker = clickTracker,
        baseUrl = config.app.baseUrl,
        maxCodeAttempts = config.app.code.maxAttempts,
    )
    return AppComponents(
        linkService = service,
        createLimiter = TokenBucketRateLimiter(
            capacity = config.app.rateLimit.create.capacity,
            refillPerMinute = config.app.rateLimit.create.refillPerMinute,
        ),
        redirectLimiter = TokenBucketRateLimiter(
            capacity = config.app.rateLimit.redirect.capacity,
            refillPerMinute = config.app.rateLimit.redirect.refillPerMinute,
        ),
        maxBodyBytes = config.app.maxBodyBytes,
    )
}
