package no.mlz.shortener.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

// Non-secret defaults live in application.conf; secrets arrive via ${?VAR} env substitution.
// load() fails fast if a required secret is missing, so the app can never boot with a blank
// DB password.
data class AppConfig(
    val server: ServerConfig,
    val app: AppSettings,
    val db: DbConfig,
) {
    data class ServerConfig(val host: String, val port: Int)

    data class AppSettings(
        val baseUrl: String,
        val maxBodyBytes: Long,
        val allowedOrigins: List<String>,
        val rateLimit: RateLimitSettings,
        val code: CodeSettings,
        // Blank when unset: management routes are then open (local dev). Set in production.
        val internalApiKey: String?,
    )

    data class RateLimitSettings(
        val create: BucketSettings,
        val redirect: BucketSettings,
    )

    data class BucketSettings(val capacity: Long, val refillPerMinute: Long)

    data class CodeSettings(val length: Int, val maxAttempts: Int)

    data class DbConfig(
        val url: String,
        val user: String,
        val password: String,
        val maxPoolSize: Int,
        val connectionTimeoutMs: Long,
        val statementTimeoutMs: Long,
    )

    companion object {
        fun load(config: Config = ConfigFactory.load()): AppConfig {
            requireSecrets(config)

            return AppConfig(
                server = ServerConfig(
                    host = config.getString("server.host"),
                    port = config.getInt("server.port"),
                ),
                app = AppSettings(
                    baseUrl = config.getString("app.baseUrl").trimEnd('/'),
                    maxBodyBytes = config.getLong("app.maxBodyBytes"),
                    allowedOrigins = config.getString("app.cors.allowedOrigins")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                    rateLimit = RateLimitSettings(
                        create = bucket(config, "app.rateLimit.create"),
                        redirect = bucket(config, "app.rateLimit.redirect"),
                    ),
                    code = CodeSettings(
                        length = config.getInt("app.code.length"),
                        maxAttempts = config.getInt("app.code.maxAttempts"),
                    ),
                    internalApiKey = config.getString("app.internalApiKey").ifBlank { null },
                ),
                db = DbConfig(
                    url = config.getString("db.url"),
                    user = config.getString("db.user"),
                    password = config.getString("db.password"),
                    maxPoolSize = config.getInt("db.maxPoolSize"),
                    connectionTimeoutMs = config.getLong("db.connectionTimeoutMs"),
                    statementTimeoutMs = config.getLong("db.statementTimeoutMs"),
                ),
            )
        }

        private fun bucket(config: Config, path: String) = BucketSettings(
            capacity = config.getLong("$path.capacity"),
            refillPerMinute = config.getLong("$path.refillPerMinute"),
        )

        private val REQUIRED_SECRETS = listOf("db.url", "db.user", "db.password")

        private fun requireSecrets(config: Config) {
            val missing = REQUIRED_SECRETS.filter { path ->
                !config.hasPath(path) || config.getString(path).isBlank()
            }
            check(missing.isEmpty()) {
                val envNames = missing.joinToString(", ") { secretEnvName(it) }
                "Missing required configuration from environment: $envNames. " +
                    "Refusing to start. See .env.example."
            }
        }

        private fun secretEnvName(path: String) = when (path) {
            "db.url" -> "DATABASE_URL"
            "db.user" -> "DATABASE_USER"
            "db.password" -> "DATABASE_PASSWORD"
            else -> path
        }
    }
}
