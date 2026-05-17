package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.SongTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_38__AddLastProviderEnrichment : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)
        val statements = tempConnection {
            SchemaUtils.addMissingColumnsStatements(SongTable, AlbumTable)
        }
        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
