package no.mlz.shortener.routes

import no.mlz.shortener.config.AppConfig
import no.mlz.shortener.module
import no.mlz.shortener.repository.Database
import no.mlz.shortener.repository.LinkRepository
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LinkRoutesTest {

    private val postgres = PostgreSQLContainer("postgres:18-alpine")
    private lateinit var database: Database
    private lateinit var repository: LinkRepository

    @BeforeAll
    fun startDatabase() {
        postgres.start()
        database = Database(
            AppConfig.DbConfig(
                url = postgres.jdbcUrl,
                user = postgres.username,
                password = postgres.password,
                maxPoolSize = 4,
                connectionTimeoutMs = 10_000,
                statementTimeoutMs = 5_000,
            ),
        )
        database.migrate()
        repository = LinkRepository(database)
    }

    @AfterAll
    fun stopDatabase() {
        database.close()
        postgres.stop()
    }

    @BeforeEach
    fun clean() {
        database.withConnection { conn ->
            conn.prepareStatement("TRUNCATE clicks, links RESTART IDENTITY CASCADE").execute()
        }
    }

    private fun config(
        createCapacity: Long = 1000,
        redirectCapacity: Long = 1000,
        internalApiKey: String? = null,
    ) = AppConfig(
        server = AppConfig.ServerConfig("0.0.0.0", 8080),
        app = AppConfig.AppSettings(
            baseUrl = "https://sho.rt",
            maxBodyBytes = 16384,
            allowedOrigins = emptyList(),
            rateLimit = AppConfig.RateLimitSettings(
                create = AppConfig.BucketSettings(createCapacity, createCapacity),
                redirect = AppConfig.BucketSettings(redirectCapacity, redirectCapacity),
            ),
            code = AppConfig.CodeSettings(length = 7, maxAttempts = 5),
            internalApiKey = internalApiKey,
            trustProxyHeaders = false,
            blockedHosts = emptyList(),
            blockedHostsFile = null,
        ),
        db = AppConfig.DbConfig("unused", "unused", "unused", 1, 1000, 1000),
    )

    private fun appTest(
        cfg: AppConfig = config(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { module(cfg, repository) }
        block()
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json() }
        followRedirects = false
    }

    private suspend fun ApplicationTestBuilder.createLink(targetUrl: String): CreateLinkResponse =
        jsonClient().post("/links") {
            contentType(ContentType.Application.Json)
            setBody("""{"targetUrl":"$targetUrl"}""")
        }.body()

    @Test
    fun `create then redirect happy path`() = appTest {
        val created = createLink("https://example.com/landing")
        assertTrue(created.code.isNotBlank())
        assertEquals("https://sho.rt/${created.code}", created.shortUrl)
        assertTrue(created.ownerToken.isNotBlank())

        val redirect = jsonClient().get("/${created.code}")
        assertEquals(HttpStatusCode.Found, redirect.status)
        assertEquals("https://example.com/landing", redirect.headers[HttpHeaders.Location])
    }

    @Test
    fun `stats and delete require the owner token`() = appTest {
        val created = createLink("https://example.com")
        val client = jsonClient()

        // Wrong / missing token -> 404 (not 403), so existence can't be probed.
        assertEquals(HttpStatusCode.NotFound, client.get("/links/${created.code}/stats").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/links/${created.code}/stats") { header(HttpHeaders.Authorization, "Bearer wrong") }.status,
        )
        assertEquals(HttpStatusCode.NotFound, client.delete("/links/${created.code}").status)

        // Correct token -> stats 200, delete 204, then gone.
        val auth = "Bearer ${created.ownerToken}"
        val stats = client.get("/links/${created.code}/stats") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, stats.status)
        val body = stats.body<StatsResponse>()
        assertTrue(body.totalClicks >= 0)

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/links/${created.code}") { header(HttpHeaders.Authorization, auth) }.status,
        )
        assertEquals(HttpStatusCode.NotFound, client.get("/${created.code}").status)
    }

    @Test
    fun `rejects dangerous url schemes`() = appTest {
        for (bad in listOf("javascript:alert(1)", "data:text/html,<h1>x</h1>")) {
            val response = jsonClient().post("/links") {
                contentType(ContentType.Application.Json)
                setBody("""{"targetUrl":"$bad"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "expected 400 for $bad")
            assertNoStackTrace(response)
        }
    }

    @Test
    fun `rejects private and metadata targets`() = appTest {
        for (bad in listOf("http://127.0.0.1/admin", "http://169.254.169.254/latest/meta-data")) {
            val response = jsonClient().post("/links") {
                contentType(ContentType.Application.Json)
                setBody("""{"targetUrl":"$bad"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "expected 400 for $bad")
        }
    }

    @Test
    fun `sql injection payloads are inert`() = appTest {
        // As a target URL: rejected as an invalid URL, no 500.
        val asTarget = jsonClient().post("/links") {
            contentType(ContentType.Application.Json)
            setBody("""{"targetUrl":"'; DROP TABLE links; --"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, asTarget.status)

        // As a path param: treated as a lookup key -> 404, table intact.
        assertEquals(HttpStatusCode.NotFound, jsonClient().get("/abc'--").status)
        // A valid create still works afterwards, proving the table survived.
        assertTrue(createLink("https://example.com").code.isNotBlank())
    }

    @Test
    fun `oversized body returns 413`() = appTest {
        val huge = "https://example.com/" + "a".repeat(20_000)
        val response = jsonClient().post("/links") {
            contentType(ContentType.Application.Json)
            setBody("""{"targetUrl":"$huge"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertNoStackTrace(response)
    }

    @Test
    fun `malformed json returns clean 400`() = appTest {
        val response = jsonClient().post("/links") {
            contentType(ContentType.Application.Json)
            setBody("{ this is not json ")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNoStackTrace(response)
    }

    @Test
    fun `rate limit returns 429 with retry-after`() = appTest(config(createCapacity = 2)) {
        val client = jsonClient()
        repeat(2) {
            val ok = client.post("/links") {
                contentType(ContentType.Application.Json)
                setBody("""{"targetUrl":"https://example.com"}""")
            }
            assertEquals(HttpStatusCode.Created, ok.status)
        }
        val limited = client.post("/links") {
            contentType(ContentType.Application.Json)
            setBody("""{"targetUrl":"https://example.com"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `sets security headers on every response`() = appTest {
        val response = jsonClient().get("/does-not-exist")
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("default-src 'none'", response.headers["Content-Security-Policy"])
        assertNotNull(response.headers["Strict-Transport-Security"])
    }

    @Test
    fun `management routes require the internal key when configured`() = appTest(config(internalApiKey = "s3cret")) {
        val client = jsonClient()
        fun postBody(): String = """{"targetUrl":"https://example.com"}"""

        // Missing and wrong keys are indistinguishable from a genuine 404.
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/links") { contentType(ContentType.Application.Json); setBody(postBody()) }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/links") {
                header("X-Internal-Key", "nope")
                contentType(ContentType.Application.Json); setBody(postBody())
            }.status,
        )

        val created = client.post("/links") {
            header("X-Internal-Key", "s3cret")
            contentType(ContentType.Application.Json); setBody(postBody())
        }
        assertEquals(HttpStatusCode.Created, created.status)

        // The redirect stays public even when the key gate is on.
        val code = created.body<CreateLinkResponse>().code
        assertEquals(HttpStatusCode.Found, client.get("/$code").status)
    }

    @Test
    fun `health and openapi spec are public`() = appTest(config(internalApiKey = "s3cret")) {
        val client = jsonClient()
        assertEquals(HttpStatusCode.OK, client.get("/health").status)

        val spec = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, spec.status)
        assertTrue(spec.bodyAsText().contains("openapi:"))
    }

    private suspend fun assertNoStackTrace(response: HttpResponse) {
        val text = response.bodyAsText()
        assertFalse(text.contains("Exception"), "response leaked an exception: $text")
        assertFalse(text.contains("\tat "), "response leaked a stack trace: $text")
    }
}
