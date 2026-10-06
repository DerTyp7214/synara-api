package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.AlbumVersionGroupTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

class V1_107__AddAlbumVersionGroup : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val statements = tempConnection {
            SchemaUtils.createStatements(AlbumVersionGroupTable) +
                    SchemaUtils.addMissingColumnsStatements(AlbumTable)
        }.map {
            it.replaceFirst("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
        }

        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
