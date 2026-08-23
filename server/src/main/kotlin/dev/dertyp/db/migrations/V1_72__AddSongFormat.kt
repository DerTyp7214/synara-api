package dev.dertyp.db.migrations

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_72__AddSongFormat : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val hasColumn = context.connection.metaData.getColumns(null, null, "song", "format").use { it.next() }
        if (hasColumn) return

        context.connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE song ADD COLUMN format VARCHAR(8) DEFAULT 'flac' NOT NULL")
        }
    }
}
