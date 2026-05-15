package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.ImageMetadataTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_33__UpdateImageMetadata : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val statements = tempConnection {
            MigrationUtils.statementsRequiredForDatabaseMigration(ImageMetadataTable)
        }

        context.connection.createStatement().use { statement ->
            for (sql in statements) {
                statement.execute(sql)
            }
            statement.execute("DELETE FROM image_metadata")
        }
    }
}
