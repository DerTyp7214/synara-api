package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_85__HueBridgeOwner : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val hasCreatedBy = context.connection.metaData
            .getColumns(null, null, "hue_bridge", "createdBy")
            .use { it.next() }
        val isPostgres = context.connection.metaData.databaseProductName.contains("PostgreSQL", ignoreCase = true)

        context.connection.createStatement().use { statement ->
            if (hasCreatedBy) {
                statement.execute("ALTER TABLE hue_bridge RENAME COLUMN \"createdBy\" TO \"userId\"")
            }
            if (isPostgres) {
                statement.execute("ALTER TABLE hue_bridge DROP CONSTRAINT IF EXISTS hue_bridge_bridgeId_unique")
            } else {
                statement.execute("DROP INDEX IF EXISTS hue_bridge_bridgeId_unique")
            }
            statement.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS hue_bridge_userId_bridgeId_unique ON hue_bridge (\"userId\", \"bridgeId\")"
            )
        }
    }
}
