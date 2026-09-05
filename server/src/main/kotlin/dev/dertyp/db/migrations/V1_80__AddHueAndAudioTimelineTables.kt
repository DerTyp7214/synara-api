package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.HueBridgeTable
import dev.dertyp.db.HueUserLinkTable
import dev.dertyp.db.SongAudioTimelineTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_80__AddHueAndAudioTimelineTables : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val statements = tempConnection {
            SchemaUtils.createStatements(HueBridgeTable, HueUserLinkTable, SongAudioTimelineTable)
        }
        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
