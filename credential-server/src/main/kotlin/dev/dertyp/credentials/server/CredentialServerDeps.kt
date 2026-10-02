package dev.dertyp.credentials.server

import dev.dertyp.credentials.CredentialJson
import dev.dertyp.credentials.server.broker.CredentialResolver
import dev.dertyp.credentials.server.broker.TidalDeviceLoginManager
import dev.dertyp.credentials.server.crypto.SecretBox
import dev.dertyp.credentials.server.crypto.TokenIssuer
import dev.dertyp.credentials.server.db.CredentialStore
import dev.dertyp.credentials.server.db.SigningKeyStore
import dev.dertyp.credentials.server.db.connectDatabase
import dev.dertyp.credentials.server.db.createDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.sql.DataSource

data class CredentialServerDeps(
    val settings: CredentialServerSettings,
    val dataSource: DataSource,
    val store: CredentialStore,
    val signingKeys: SigningKeyStore,
    val tokenIssuer: TokenIssuer,
    val httpClient: HttpClient,
    val scope: CoroutineScope,
    val resolver: CredentialResolver,
    val tidalLogins: TidalDeviceLoginManager,
) : AutoCloseable {
    val admin = CredentialAdminOperations(store, resolver, tidalLogins)

    override fun close() {
        scope.cancel()
        httpClient.close()
        (dataSource as? AutoCloseable)?.close()
    }

    companion object {
        fun create(config: ApplicationConfig): CredentialServerDeps {
            val settings = CredentialServerSettings.fromConfig(config)
            return create(settings, createDataSource(settings.database), defaultHttpClient())
        }

        fun create(settings: CredentialServerSettings, dataSource: DataSource, httpClient: HttpClient): CredentialServerDeps {
            val box = SecretBox.create(settings.masterKey, settings.keyFile)
            val database = connectDatabase(dataSource)
            val store = CredentialStore(database, box)
            val signingKeys = SigningKeyStore(database, box)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            return CredentialServerDeps(
                settings = settings,
                dataSource = dataSource,
                store = store,
                signingKeys = signingKeys,
                tokenIssuer = TokenIssuer(signingKeys, settings.issuer, settings.tokenTtlSeconds),
                httpClient = httpClient,
                scope = scope,
                resolver = CredentialResolver(store, httpClient),
                tidalLogins = TidalDeviceLoginManager(store, httpClient, scope),
            )
        }

        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(CredentialJson.json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
    }
}
