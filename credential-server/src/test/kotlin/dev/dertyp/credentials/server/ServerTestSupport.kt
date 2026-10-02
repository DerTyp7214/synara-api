package dev.dertyp.credentials.server

import dev.dertyp.credentials.CredentialJson
import dev.dertyp.credentials.server.db.createDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import java.nio.file.Path

const val TEST_ADMIN_KEY = "test-admin-key"

fun testSettings(dir: Path, adminKey: String = TEST_ADMIN_KEY, tokenTtlSeconds: Long = 900) = CredentialServerSettings(
    adminKey = adminKey,
    masterKey = "",
    keyFile = dir.resolve("master.key"),
    issuer = "synara-credentials-test",
    tokenTtlSeconds = tokenTtlSeconds,
    database = DatabaseSettings(
        driverClassName = "org.sqlite.JDBC",
        jdbcUrl = "jdbc:sqlite:${dir.resolve("credentials.db")}",
        user = "",
        password = "",
    ),
)

fun offlineHttpClient(): HttpClient = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })

fun testDeps(
    dir: Path,
    adminKey: String = TEST_ADMIN_KEY,
    httpClient: HttpClient = offlineHttpClient(),
    tokenTtlSeconds: Long = 900,
): CredentialServerDeps {
    val settings = testSettings(dir, adminKey, tokenTtlSeconds)
    return CredentialServerDeps.create(settings, createDataSource(settings.database), httpClient)
}

fun ApplicationTestBuilder.jsonClient() = createClient {
    install(ContentNegotiation) { json(CredentialJson.json) }
}
