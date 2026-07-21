package no.mlz.shortener.repository

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import no.mlz.shortener.config.AppConfig
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Owns the application's HikariCP pool and exposes tiny, hand-written query helpers.
 *
 * There is no ORM: every statement is a [PreparedStatement] with bound parameters
 * (see [Connection.query] / [Connection.update]). Nothing interpolates user input into SQL.
 */
class Database(dbConfig: AppConfig.DbConfig) : AutoCloseable {

    val dataSource: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = dbConfig.url
            username = dbConfig.user
            password = dbConfig.password
            maximumPoolSize = dbConfig.maxPoolSize
            connectionTimeout = dbConfig.connectionTimeoutMs
            poolName = "shortener-pool"
            // A runaway query cannot pin a connection forever: bound every statement server-side.
            connectionInitSql = "SET statement_timeout = ${dbConfig.statementTimeoutMs}"
        },
    )

    /**
     * Applies Flyway migrations. Migrations should run under a privileged role that can create
     * tables; the app's [dataSource] is a least-privilege role that only has DML (§2.9). Pass
     * [migrationUser]/[migrationPassword] to migrate under that separate role (this is what
     * Docker Compose and CI do). When absent, migrations reuse the app datasource — convenient
     * for local `./gradlew run` and documented as such in the README.
     */
    fun migrate(migrationUser: String? = null, migrationPassword: String? = null) {
        if (migrationUser != null && migrationPassword != null) {
            // Short-lived pool under the privileged role, closed as soon as migration finishes.
            migrationDataSource(migrationUser, migrationPassword).use { runFlyway(it) }
        } else {
            runFlyway(dataSource)
        }
    }

    private fun runFlyway(source: DataSource) {
        Flyway.configure()
            .dataSource(source)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    private fun migrationDataSource(user: String, password: String): HikariDataSource {
        val appJdbcUrl = dataSource.jdbcUrl // capture before apply: HikariConfig has its own `dataSource`
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = appJdbcUrl
                username = user
                this.password = password
                maximumPoolSize = 2
                poolName = "shortener-migration"
            },
        )
    }

    fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use(block)

    override fun close() = dataSource.close()
}

private fun PreparedStatement.bindAll(params: Array<out Any?>) {
    params.forEachIndexed { index, value -> setObject(index + 1, value) }
}

/** Runs a parameterized SELECT and maps every row. */
fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { stmt ->
        stmt.bindAll(params)
        stmt.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

/** Runs a parameterized SELECT and maps the first row, or null. */
fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    prepareStatement(sql).use { stmt ->
        stmt.bindAll(params)
        stmt.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
    }

/** Runs a parameterized INSERT/UPDATE/DELETE and returns the affected row count. */
fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { stmt ->
        stmt.bindAll(params)
        stmt.executeUpdate()
    }

/** Convenience for callers that only have a [DataSource]. */
fun <T> DataSource.withConnection(block: (Connection) -> T): T =
    connection.use(block)
