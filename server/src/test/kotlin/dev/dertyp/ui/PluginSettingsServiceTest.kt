package dev.dertyp.ui

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.PluginSettingTable
import dev.dertyp.dbQuery
import dev.dertyp.services.ui.PluginSettingsService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginSettingsServiceTest {
    private val service = PluginSettingsService()

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "plugin_settings_test")
        dbQuery { SchemaUtils.create(PluginSettingTable) }
    }

    @AfterEach
    fun tearDown() = TestDatabase.cleanUp()

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `settings are stored per plugin and nulls delete`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val a = service.forPlugin("a")
        val b = service.forPlugin("b")

        a.set("key", "1")
        b.setAll(mapOf("key" to "2", "other" to "x"))
        assertEquals("1", a.get("key"))
        assertEquals(mapOf("key" to "2", "other" to "x"), b.getAll())

        a.set("key", "3")
        assertEquals("3", a.get("key"))

        b.set("other", null)
        assertEquals(mapOf("key" to "2"), b.getAll())
        assertNull(b.get("other"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `changes emits the current map first and after every write`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val settings = service.forPlugin("live")
        settings.set("k", "v1")
        assertEquals(mapOf("k" to "v1"), settings.changes().first())

        val emissions = mutableListOf<Map<String, String>>()
        val job = launch { settings.changes().take(2).toList(emissions) }
        while (emissions.isEmpty()) yield()
        settings.set("k", "v2")
        job.join()
        assertEquals(listOf(mapOf("k" to "v1"), mapOf("k" to "v2")), emissions)
    }
}
