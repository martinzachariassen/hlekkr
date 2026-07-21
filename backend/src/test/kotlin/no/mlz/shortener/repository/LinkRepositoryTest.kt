package no.mlz.shortener.repository

import no.mlz.shortener.config.AppConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.time.OffsetDateTime
import java.time.ZoneOffset

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LinkRepositoryTest {

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

    @Test
    fun `inserts and finds a live link`() {
        val id = repository.insert("abc1234", "https://example.com", "h".repeat(64), null)
        val found = repository.findLive("abc1234")

        assertNotNull(found)
        assertEquals(id, found!!.id)
        assertEquals("https://example.com", found.targetUrl)
    }

    @Test
    fun `rejects a duplicate code`() {
        repository.insert("dupe123", "https://example.com", "h".repeat(64), null)
        assertThrows(DuplicateCodeException::class.java) {
            repository.insert("dupe123", "https://other.com", "h".repeat(64), null)
        }
    }

    @Test
    fun `treats a sql injection payload as inert data`() {
        val payload = "'; DROP TABLE links; --"
        repository.insert("evil123", payload, "h".repeat(64), null)

        val found = repository.findLive("evil123")
        assertEquals(payload, found?.targetUrl)

        // Querying with the payload as the code executes nothing: null, and the table is intact.
        assertNull(repository.findLive(payload))
        assertNotNull(repository.findLive("evil123"))
    }

    @Test
    fun `excludes expired and deleted links from findLive`() {
        repository.insert("expird1", "https://example.com", "h".repeat(64), OffsetDateTime.now(ZoneOffset.UTC).minusHours(1))
        assertNull(repository.findLive("expird1"))

        repository.insert("delete1", "https://example.com", "h".repeat(64), null)
        assertTrue(repository.softDelete("delete1"))
        assertNull(repository.findLive("delete1"))
        assertFalse(repository.softDelete("delete1")) // second soft-delete is a no-op
    }

    @Test
    fun `records clicks and aggregates counts`() {
        val id = repository.insert("clicks1", "https://example.com", "h".repeat(64), null)

        repository.recordClicks(listOf(id, id, id))

        assertEquals(3, repository.totalClicks(id))
        val daily = repository.clicksLast7Days(id)
        assertEquals(1, daily.size)
        assertEquals(3, daily.first().count)
    }
}
