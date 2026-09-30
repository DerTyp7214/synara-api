package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.AlbumArtistTable
import dev.dertyp.db.SongArtistTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_104__AddArtistCreditOrder : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val statements = tempConnection {
            SchemaUtils.addMissingColumnsStatements(SongArtistTable, AlbumArtistTable)
        }.map { it.replaceFirst("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ") }

        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
