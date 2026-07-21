package no.mlz.shortener

import no.mlz.shortener.config.AppConfig
import no.mlz.shortener.plugins.configureCors
import no.mlz.shortener.plugins.configureOpenApi
import no.mlz.shortener.plugins.configureSerialization
import no.mlz.shortener.plugins.configureStatusPages
import no.mlz.shortener.repository.Database
import no.mlz.shortener.repository.LinkRepository
import no.mlz.shortener.routes.linkRoutes
import no.mlz.shortener.security.CodeGenerator
import no.mlz.shortener.security.HostBlocklist
import no.mlz.shortener.security.TokenBucketRateLimiter
import no.mlz.shortener.security.installSecurityHeaders
import no.mlz.shortener.service.ClickTracker
import no.mlz.shortener.service.LinkService
import no.mlz.shortener.service.UrlValidator
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class AppComponents(
    val linkService: LinkService,
    val createLimiter: TokenBucketRateLimiter,
    val redirectLimiter: TokenBucketRateLimiter,
    val maxBodyBytes: Long,
    val internalApiKey: String?,
    val checkReadiness: () -> Boolean,
)

fun main() {
    val config = AppConfig.load()
    val database = Database(config.db)
    // Off in production, where migrations are a separate privileged step (the app role has no DDL
    // rights); on by default so local `./gradlew run` bootstraps the schema. See README §Database hardening.
    if (System.getenv("RUN_MIGRATIONS_ON_STARTUP")?.toBoolean() != false) {
        database.migrate(
            migrationUser = System.getenv("DATABASE_MIGRATION_USER"),
            migrationPassword = System.getenv("DATABASE_MIGRATION_PASSWORD"),
        )
    }
    // Close the pool through the app's ordered stop sequence (after the final click flush), not a
    // bare JVM hook that would race that flush and drop the last batch.
    embeddedServer(Netty, host = config.server.host, port = config.server.port) {
        module(config, LinkRepository(database), onStopped = database::close)
    }.start(wait = true)
}

fun Application.module(config: AppConfig, repository: LinkRepository, onStopped: () -> Unit = {}) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clickTracker = ClickTracker(repository, appScope).also { it.start() }

    val blocklist = HostBlocklist.from(config.app.blockedHosts.joinToString(","), config.app.blockedHostsFile)

    val components = buildComponents(config, repository, clickTracker, blocklist)
    if (components.internalApiKey == null) {
        environment.log.warn("INTERNAL_API_KEY is unset: management routes (POST/stats/delete) are UNAUTHENTICATED.")
    }

    // Non-secret boot summary: leaves an auditable record of the effective security posture. Never
    // add DB credentials, the connection string, or the internal key here.
    environment.log.info(
        "Shortener started: host=${config.server.host} port=${config.server.port} " +
            "baseUrl=${config.app.baseUrl} auth=${if (components.internalApiKey != null) "enabled" else "OPEN"} " +
            "trustProxyHeaders=${config.app.trustProxyHeaders} corsOrigins=${config.app.allowedOrigins.size} " +
            "blockedDomains=${blocklist.size} " +
            "rateLimit[create=${config.app.rateLimit.create.refillPerMinute}/min " +
            "redirect=${config.app.rateLimit.redirect.refillPerMinute}/min]",
    )

    // useLastProxy() takes the rightmost X-Forwarded-For entry (the one the trusted proxy appends)
    // so a client can't prepend a spoofed IP to escape its rate-limit bucket.
    if (config.app.trustProxyHeaders) {
        install(XForwardedHeaders) { useLastProxy() }
    }

    install(CallId) {
        header("X-Correlation-Id")
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
    }
    installSecurityHeaders()
    configureSerialization()
    configureStatusPages()
    configureCors(config.app.allowedOrigins)
    configureOpenApi()

    linkRoutes(components)

    // Flush pending clicks (still needs the DB pool) before anything tears it down...
    monitor.subscribe(ApplicationStopping) {
        runBlocking { clickTracker.close() }
        appScope.cancel()
    }
    // ...then close external resources once the server has fully stopped.
    monitor.subscribe(ApplicationStopped) {
        onStopped()
    }
}

private fun buildComponents(
    config: AppConfig,
    repository: LinkRepository,
    clickTracker: ClickTracker,
    blocklist: HostBlocklist,
): AppComponents {
    val service = LinkService(
        repository = repository,
        urlValidator = UrlValidator(config.app.baseUrl, blocklist),
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
        internalApiKey = config.app.internalApiKey,
        checkReadiness = repository::ping,
    )
}
