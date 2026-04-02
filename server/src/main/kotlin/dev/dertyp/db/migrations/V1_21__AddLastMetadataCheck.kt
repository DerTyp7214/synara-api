package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.SongTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_21__AddLastMetadataCheck : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val alterStatements = tempConnection {
            MigrationUtils.statementsRequiredForDatabaseMigration(ArtistTable, AlbumTable, SongTable)
        }

        try {
            context.connection.createStatement().use { statement ->
                for (sql in alterStatements) {
                    statement.execute(sql)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
