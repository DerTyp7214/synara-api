package dev.dertyp.config

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class EntityChangeConfigTest {
    private fun parse(vararg entries: Pair<String, String>) =
        ServerConfig(MapApplicationConfig(*entries)).entityChanges

    @Test
    fun `the retention defaults to thirty days`() {
        assertEquals(EntityChangeConfig(retentionDays = 30), parse())
        assertEquals(30, EntityChangeConfig().retentionDays)
    }

    @Test
    fun `the retention is read from the configuration`() {
        assertEquals(EntityChangeConfig(retentionDays = 7), parse("entityChanges.retentionDays" to "7"))
    }

    @Test
    fun `a value that is not a number falls back to the default`() {
        assertEquals(EntityChangeConfig(retentionDays = 30), parse("entityChanges.retentionDays" to "soon"))
    }

    @Test
    fun `a retention below one day is raised to one day`() {
        assertEquals(EntityChangeConfig(retentionDays = 1), parse("entityChanges.retentionDays" to "0"))
        assertEquals(EntityChangeConfig(retentionDays = 1), parse("entityChanges.retentionDays" to "-5"))
        assertEquals(EntityChangeConfig(retentionDays = 1), parse("entityChanges.retentionDays" to "1"))
    }
}
