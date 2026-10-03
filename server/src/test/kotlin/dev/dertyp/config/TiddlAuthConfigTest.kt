package dev.dertyp.config

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TiddlAuthConfigTest {
    private fun parse(vararg entries: Pair<String, String>) =
        ServerConfig(MapApplicationConfig(*entries)).importers.tiddlAuth

    @Test
    fun `a valid value is split on the first semicolon and trimmed`() {
        assertEquals(TiddlAuthConfig("id", "se;cret"), parse("tiddl.auth" to " id ; se;cret "))
    }

    @Test
    fun `malformed values become null`() {
        assertNull(parse("tiddl.auth" to "only-id"))
        assertNull(parse("tiddl.auth" to "id;"))
        assertNull(parse("tiddl.auth" to ";secret"))
    }

    @Test
    fun `blank and missing values become null`() {
        assertNull(parse("tiddl.auth" to "  "))
        assertNull(parse())
    }
}
