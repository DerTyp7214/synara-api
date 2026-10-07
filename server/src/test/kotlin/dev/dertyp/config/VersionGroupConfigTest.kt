package dev.dertyp.config

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class VersionGroupConfigTest {
    private fun parse(vararg entries: Pair<String, String>) =
        ServerConfig(MapApplicationConfig(*entries)).versionGroups

    @Test
    fun `the rebuild waits default to one and five seconds`() {
        assertEquals(VersionGroupConfig(rebuildQuietPeriod = 1.seconds, rebuildMaxWait = 5.seconds), parse())
        assertEquals(VersionGroupConfig(1.seconds, 5.seconds), VersionGroupConfig())
    }

    @Test
    fun `the rebuild waits are read from the configuration`() {
        assertEquals(
            VersionGroupConfig(3.seconds, 20.seconds),
            parse("versionGroups.rebuildQuietSeconds" to "3", "versionGroups.rebuildMaxWaitSeconds" to "20")
        )
    }

    @Test
    fun `a value that is not a number falls back to the default`() {
        assertEquals(
            VersionGroupConfig(1.seconds, 5.seconds),
            parse("versionGroups.rebuildQuietSeconds" to "soon", "versionGroups.rebuildMaxWaitSeconds" to "later")
        )
    }

    @Test
    fun `a quiet period below one second is raised to one second`() {
        assertEquals(VersionGroupConfig(1.seconds, 5.seconds), parse("versionGroups.rebuildQuietSeconds" to "0"))
        assertEquals(VersionGroupConfig(1.seconds, 5.seconds), parse("versionGroups.rebuildQuietSeconds" to "-4"))
    }

    @Test
    fun `a maximum wait below the quiet period is raised to the quiet period`() {
        assertEquals(
            VersionGroupConfig(4.seconds, 4.seconds),
            parse("versionGroups.rebuildQuietSeconds" to "4", "versionGroups.rebuildMaxWaitSeconds" to "2")
        )
        assertEquals(VersionGroupConfig(8.seconds, 8.seconds), parse("versionGroups.rebuildQuietSeconds" to "8"))
        assertEquals(VersionGroupConfig(1.seconds, 1.seconds), parse("versionGroups.rebuildMaxWaitSeconds" to "-3"))
    }
}
