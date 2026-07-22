package no.mlz.shortener.repository

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import no.mlz.shortener.config.AppConfig
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

class Database(dbConfig: AppConfig.DbConfig) : AutoCloseable {

    val dataSource: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = dbConfig.url
            username = dbConfig.user
            password = dbConfig.password
            maximumPoolSize = dbConfig.maxPoolSize
            connectionTimeout = dbConfig.connectionTimeoutMs
            poolName = "shortener-pool"
            // Server-side bound so a runaway query can't pin a connection forever.
            connectionInitSql = "SET statement_timeout = ${dbConfig.statementTimeoutMs}"
        },
    )

    // The app role is DML-only, so migrations run under a separate privileged role when supplied;
    // absent, they reuse the app datasource (local single-role runs).
    fun migrate(migrationUser: String? = null, migrationPassword: String? = null) {
        if (migrationUser != null && migrationPassword != null) {
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
        val appJdbcUrl = dataSource.jdbcUrl // capture before apply {}: HikariConfig shadows `dataSource`
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

fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { stmt ->
        stmt.bindAll(params)
        stmt.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    prepareStatement(sql).use { stmt ->
        stmt.bindAll(params)
        stmt.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
    }

fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { stmt ->
        stmt.bindAll(params)
        stmt.executeUpdate()
    }
