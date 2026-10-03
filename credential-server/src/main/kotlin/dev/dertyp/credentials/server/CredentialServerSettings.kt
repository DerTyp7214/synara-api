package dev.dertyp.credentials.server

import io.ktor.server.config.ApplicationConfig
import java.nio.file.Path

data class DatabaseSettings(
    val driverClassName: String,
    val jdbcUrl: String,
    val user: String,
    val password: String,
)

data class CredentialServerSettings(
    val adminKey: String,
    val masterKey: String,
    val keyFile: Path,
    val issuer: String,
    val tokenTtlSeconds: Long,
    val database: DatabaseSettings,
) {
    companion object {
        const val DEFAULT_ISSUER = "synara-credentials"
        const val DEFAULT_TOKEN_TTL_SECONDS = 900L

        fun fromConfig(config: ApplicationConfig): CredentialServerSettings {
            fun value(path: String, default: String = "") = config.propertyOrNull(path)?.getString() ?: default
            return CredentialServerSettings(
                adminKey = value("credentials.adminKey").trim(),
                masterKey = value("credentials.masterKey").trim(),
                keyFile = Path.of(value("credentials.keyFile", "master.key").ifBlank { "master.key" }),
                issuer = value("credentials.issuer", DEFAULT_ISSUER).ifBlank { DEFAULT_ISSUER },
                tokenTtlSeconds = value("credentials.tokenTtlSeconds").toLongOrNull()?.takeIf { it > 0 }
                    ?: DEFAULT_TOKEN_TTL_SECONDS,
                database = DatabaseSettings(
                    driverClassName = value("storage.driverClassName", "org.sqlite.JDBC").ifBlank { "org.sqlite.JDBC" },
                    jdbcUrl = value(
                        "storage.jdbcURL",
                        "jdbc:sqlite:credentials.db"
                    ).ifBlank { "jdbc:sqlite:credentials.db" },
                    user = value("storage.user"),
                    password = value("storage.password"),
                ),
            )
        }
    }
}
