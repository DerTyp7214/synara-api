package dev.dertyp.services.credentials

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.PluginSettingTable
import dev.dertyp.dbQuery
import dev.dertyp.services.podcast.index.PodcastIndexCredentialSource
import dev.dertyp.services.podcast.index.PodcastIndexCredentialSource.Companion.KEY_API_KEY
import dev.dertyp.services.podcast.index.PodcastIndexCredentialSource.Companion.KEY_API_SECRET
import dev.dertyp.services.podcast.index.PodcastIndexCredentials
import dev.dertyp.services.ui.PluginSettingsService
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialSourceTest {
    private val service = PluginSettingsService()
    private val settings = service.forPlugin(PodcastIndexCredentialSource.PLUGIN_ID)
    private val cipher = CredentialCipher(MapApplicationConfig("credentials.encryptionKey" to "test-key"))
    private val environment = mapOf("podcastIndex.apiKey" to "envKey", "podcastIndex.apiSecret" to "envSecret")

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "credential_source_test")
        dbQuery { SchemaUtils.create(PluginSettingTable) }
    }

    @AfterEach
    fun tearDown() = TestDatabase.cleanUp()

    private fun source(env: Map<String, String> = emptyMap(), with: CredentialCipher = cipher): PodcastIndexCredentialSource {
        val config = MapApplicationConfig()
        env.forEach { (key, value) -> config.put(key, value) }
        return PodcastIndexCredentialSource(settings, config, with)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stored values are encrypted at rest`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val source = source()
        source.store(mapOf(KEY_API_KEY to " key123 ", KEY_API_SECRET to "secret456", "unrelated" to "x"))

        val rows = settings.getAll()
        assertEquals(setOf(KEY_API_KEY, KEY_API_SECRET), rows.keys)
        rows.values.forEach { assertTrue(it.startsWith(CredentialCipher.PREFIX)) }
        assertFalse(rows.getValue(KEY_API_KEY).contains("key123"))
        assertFalse(rows.getValue(KEY_API_SECRET).contains("secret456"))
        assertEquals(PodcastIndexCredentials("key123", "secret456"), source.stored())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `origin prefers stored over environment over none`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        assertEquals(CredentialOrigin.NONE, source().origin())
        assertNull(source().current())

        val withEnvironment = source(environment)
        assertEquals(CredentialOrigin.ENVIRONMENT, withEnvironment.origin())
        assertEquals(PodcastIndexCredentials("envKey", "envSecret"), withEnvironment.current())

        withEnvironment.store(mapOf(KEY_API_KEY to "key123"))
        assertEquals(CredentialOrigin.ENVIRONMENT, withEnvironment.origin())

        withEnvironment.store(mapOf(KEY_API_SECRET to "secret456"))
        assertEquals(CredentialOrigin.STORED, withEnvironment.origin())
        assertEquals(PodcastIndexCredentials("key123", "secret456"), withEnvironment.current())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `values sealed with another key are unreadable`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        source().store(mapOf(KEY_API_KEY to "key123", KEY_API_SECRET to "secret456"))

        val other = CredentialCipher(MapApplicationConfig("credentials.encryptionKey" to "other-key"))
        val withoutEnvironment = source(with = other)
        assertEquals(CredentialOrigin.UNREADABLE, withoutEnvironment.origin())
        assertNull(withoutEnvironment.stored())
        assertNull(withoutEnvironment.current())

        val withEnvironment = source(environment, other)
        assertEquals(CredentialOrigin.UNREADABLE, withEnvironment.origin())
        assertEquals(PodcastIndexCredentials("envKey", "envSecret"), withEnvironment.current())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `blank values and clear delete the rows`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val source = source()
        source.store(mapOf(KEY_API_KEY to "key123", KEY_API_SECRET to "secret456"))

        source.store(mapOf(KEY_API_KEY to "   ", KEY_API_SECRET to null))
        assertTrue(settings.getAll().isEmpty())
        assertEquals(CredentialOrigin.NONE, source.origin())

        source.store(mapOf(KEY_API_KEY to "key123", KEY_API_SECRET to "secret456"))
        settings.set("unrelated", "x")
        source.clear()
        assertEquals(mapOf("unrelated" to "x"), settings.getAll())
        assertEquals(CredentialOrigin.NONE, source.origin())
    }
}
