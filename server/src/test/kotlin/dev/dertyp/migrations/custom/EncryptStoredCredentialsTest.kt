package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.PluginSettingTable
import dev.dertyp.dbQuery
import dev.dertyp.services.credentials.CredentialCipher
import dev.dertyp.services.metadata.AcoustIdCredentialSource
import dev.dertyp.services.podcast.index.PodcastIndexCredentialSource
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncryptStoredCredentialsTest : KoinTest {
    private val cipher = CredentialCipher(MapApplicationConfig("credentials.encryptionKey" to "test-key"))

    private val podcastIndex = PodcastIndexCredentialSource.PLUGIN_ID
    private val server = AcoustIdCredentialSource.PLUGIN_ID

    private fun setup(dialect: DbDialect) = runBlocking {
        startKoin { modules(module { single { cipher } }) }
        TestDatabase.connect(dialect, "encrypt_stored_credentials_test")
        dbQuery { SchemaUtils.create(PluginSettingTable) }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private suspend fun put(pluginId: String, settingKey: String, settingValue: String) = dbQuery {
        PluginSettingTable.insert {
            it[PluginSettingTable.pluginId] = pluginId
            it[key] = settingKey
            it[value] = settingValue
        }
    }

    private suspend fun rows(): Map<Pair<String, String>, String> = dbQuery {
        PluginSettingTable.selectAll().associate {
            (it[PluginSettingTable.pluginId] to it[PluginSettingTable.key]) to it[PluginSettingTable.value]
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `plain text credentials are encrypted and decrypt back`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        put(podcastIndex, PodcastIndexCredentialSource.KEY_API_KEY, " key123 ")
        put(podcastIndex, PodcastIndexCredentialSource.KEY_API_SECRET, "secret456")
        put(server, AcoustIdCredentialSource.KEY_API_KEY, "acoustKey")

        EncryptStoredCredentials().migrate()

        val rows = rows()
        val expected = mapOf(
            (podcastIndex to PodcastIndexCredentialSource.KEY_API_KEY) to "key123",
            (podcastIndex to PodcastIndexCredentialSource.KEY_API_SECRET) to "secret456",
            (server to AcoustIdCredentialSource.KEY_API_KEY) to "acoustKey",
        )
        assertEquals(expected.keys, rows.keys)
        expected.forEach { (row, plain) ->
            val stored = rows.getValue(row)
            assertTrue(cipher.isEncrypted(stored))
            assertFalse(stored.contains(plain))
            assertEquals(plain, cipher.decrypt(row.second, stored))
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `encrypted rows stay untouched and blank rows are deleted`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val sealed = cipher.encrypt(PodcastIndexCredentialSource.KEY_API_KEY, "key123")
        put(podcastIndex, PodcastIndexCredentialSource.KEY_API_KEY, sealed)
        put(podcastIndex, PodcastIndexCredentialSource.KEY_API_SECRET, "   ")

        EncryptStoredCredentials().migrate()

        assertEquals(mapOf((podcastIndex to PodcastIndexCredentialSource.KEY_API_KEY) to sealed), rows())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unrelated settings stay untouched`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        put(server, "theme", "dark")
        put("otherPlugin", PodcastIndexCredentialSource.KEY_API_KEY, "notACredential")
        put(podcastIndex, "pageSize", "20")

        EncryptStoredCredentials().migrate()

        assertEquals(
            mapOf(
                (server to "theme") to "dark",
                ("otherPlugin" to PodcastIndexCredentialSource.KEY_API_KEY) to "notACredential",
                (podcastIndex to "pageSize") to "20",
            ),
            rows(),
        )
    }
}
