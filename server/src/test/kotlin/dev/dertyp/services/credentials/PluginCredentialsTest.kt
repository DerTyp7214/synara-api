package dev.dertyp.services.credentials

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.db.PluginSettingTable
import dev.dertyp.services.credentials.FakeCredentialServer.Companion.grant
import dev.dertyp.services.ui.PluginSettingsService
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginCredentialsTest {
    private val settingsService = PluginSettingsService()
    private val cipher = CredentialCipher(MapApplicationConfig("credentials.encryptionKey" to "test-key"))
    private val store = LocalPluginCredentialStore(settingsService, cipher)
    private val remoteName = CredentialNames.plugin("remote", "token")
    private val server = FakeCredentialServer(listOf(grant(remoteName))).apply {
        credentials[remoteName] = ResolvedCredential.ApiKey(remoteName, "from-server")
    }

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "plugin_credentials_test", PluginSettingTable)
    }

    @AfterEach
    fun tearDown() = TestDatabase.cleanUp()

    private suspend fun factory(): PluginCredentialsFactory {
        val remote = server.provider()
        remote.connect()
        return PluginCredentialsFactory(remote, store)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `local plugin credentials are stored encrypted per plugin`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val credentials = factory().forPlugin("alpha")
        val value = ResolvedCredential.ApiKeyPair(CredentialNames.plugin("alpha", "api"), "key", "secret")

        assertFalse(credentials.managedRemotely)
        credentials.store("api", value)

        val raw = settingsService.get("alpha", "credential.api")!!
        assertTrue(raw.startsWith(CredentialCipher.PREFIX))
        assertFalse(raw.contains("secret"))
        assertEquals(value, credentials.get("api"))
        assertTrue(store.has(CredentialNames.plugin("alpha", "api")))

        credentials.remove("api")
        assertNull(credentials.get("api"))
        assertNull(settingsService.get("alpha", "credential.api"))
        assertFalse(store.has(CredentialNames.plugin("alpha", "api")))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a value copied to another plugin does not decrypt`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val factory = factory()
        factory.forPlugin("alpha").store("api", ResolvedCredential.ApiKey("api", "key"))

        settingsService.setAll("beta", mapOf("credential.api" to settingsService.get("alpha", "credential.api")))

        assertNull(factory.forPlugin("beta").get("api"))
        assertEquals(ResolvedCredential.ApiKey("api", "key"), factory.forPlugin("alpha").get("api"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stored plugin credentials are listed on load`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        factory().forPlugin("alpha").store("api", ResolvedCredential.ApiKey("api", "key"))
        settingsService.setAll("alpha", mapOf("unrelated" to "x"))

        val fresh = LocalPluginCredentialStore(settingsService, cipher)
        fresh.load()

        assertTrue(fresh.has(CredentialNames.plugin("alpha", "api")))
        assertFalse(fresh.has(CredentialNames.plugin("alpha", "unrelated")))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `remotely granted plugin names are fetched under their full name and are read only`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val credentials = factory().forPlugin("remote")

            assertTrue(credentials.managedRemotely)
            assertEquals("from-server", (credentials.get("token") as ResolvedCredential.ApiKey).key)
            assertEquals(1, server.fetches(remoteName))
            assertThrows<IllegalStateException> {
                runBlocking {
                    credentials.store(
                        "token",
                        ResolvedCredential.ApiKey("token", "x")
                    )
                }
            }
            assertThrows<IllegalStateException> { runBlocking { credentials.remove("token") } }

            credentials.store("other", ResolvedCredential.ApiKey("other", "local"))
            assertEquals(ResolvedCredential.ApiKey("other", "local"), credentials.get("other"))
            assertEquals(0, server.fetches(CredentialNames.plugin("remote", "other")))
        }
}
