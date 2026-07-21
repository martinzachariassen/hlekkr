package no.mlz.shortener.repository

import java.time.LocalDate
import java.time.OffsetDateTime

data class LinkRecord(
    val id: Long,
    val code: String,
    val targetUrl: String,
    val ownerTokenHash: String,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime?,
)

data class DailyClickCount(val date: LocalDate, val count: Long)

// Code collided with an existing row on insert (SQLSTATE 23505).
class DuplicateCodeException : RuntimeException()

class LinkRepository(private val database: Database) {

    fun insert(
        code: String,
        targetUrl: String,
        ownerTokenHash: String,
        expiresAt: OffsetDateTime?,
    ): Long = database.withConnection { conn ->
        try {
            conn.queryOne(
                "INSERT INTO links (code, target_url, owner_token_hash, expires_at) " +
                    "VALUES (?, ?, ?, ?) RETURNING id",
                code, targetUrl, ownerTokenHash, expiresAt,
                map = { it.getLong("id") },
            ) ?: error("INSERT ... RETURNING id returned no row")
        } catch (e: org.postgresql.util.PSQLException) {
            if (e.sqlState == UNIQUE_VIOLATION) throw DuplicateCodeException() else throw e
        }
    }

    fun findLive(code: String): LinkRecord? = database.withConnection { conn ->
        conn.queryOne(
            "SELECT id, code, target_url, owner_token_hash, created_at, expires_at " +
                "FROM links " +
                "WHERE code = ? AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at > now())",
            code,
            map = ::toLinkRecord,
        )
    }

    fun softDelete(code: String): Boolean = database.withConnection { conn ->
        conn.update(
            "UPDATE links SET deleted_at = now() WHERE code = ? AND deleted_at IS NULL",
            code,
        ) > 0
    }

    fun recordClicks(linkIds: List<Long>) {
        if (linkIds.isEmpty()) return
        database.withConnection { conn ->
            conn.prepareStatement("INSERT INTO clicks (link_id) VALUES (?)").use { stmt ->
                for (id in linkIds) {
                    stmt.setLong(1, id)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun totalClicks(linkId: Long): Long = database.withConnection { conn ->
        conn.queryOne(
            "SELECT count(*) AS c FROM clicks WHERE link_id = ?",
            linkId,
            map = { it.getLong("c") },
        ) ?: 0L
    }

    // Ascending, UTC; zero-click days are absent from the result.
    fun clicksLast7Days(linkId: Long): List<DailyClickCount> = database.withConnection { conn ->
        conn.query(
            "SELECT (clicked_at AT TIME ZONE 'UTC')::date AS d, count(*) AS c " +
                "FROM clicks " +
                "WHERE link_id = ? AND clicked_at >= now() - interval '7 days' " +
                "GROUP BY d ORDER BY d",
            linkId,
            map = { DailyClickCount(it.getObject("d", LocalDate::class.java), it.getLong("c")) },
        )
    }

    private fun toLinkRecord(rs: java.sql.ResultSet) = LinkRecord(
        id = rs.getLong("id"),
        code = rs.getString("code"),
        targetUrl = rs.getString("target_url"),
        ownerTokenHash = rs.getString("owner_token_hash"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java),
    )

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}
