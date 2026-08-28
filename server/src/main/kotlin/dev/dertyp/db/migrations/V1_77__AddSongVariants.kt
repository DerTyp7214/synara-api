package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.SongTable
import dev.dertyp.db.SongVariantTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_77__AddSongVariants : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val statements = tempConnection {
            SchemaUtils.createStatements(SongVariantTable) + SchemaUtils.addMissingColumnsStatements(SongTable)
        }
        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }

        val hasAtmosPath = context.connection.metaData
            .getColumns(null, null, "song", "atmosPath")
            .use { it.next() }
        if (!hasAtmosPath) return

        context.connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO song_variant (\"songId\", kind, path) " +
                        "SELECT id, 'ATMOS', \"atmosPath\" FROM song WHERE \"atmosPath\" IS NOT NULL"
            )
            statement.execute("ALTER TABLE song DROP COLUMN \"atmosPath\"")
        }
    }
}
