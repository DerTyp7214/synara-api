package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.CollectionTable
import dev.dertyp.db.UserPlaylistTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_79__AddImageSource : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val statements = tempConnection {
            SchemaUtils.addMissingColumnsStatements(UserPlaylistTable, CollectionTable)
        }
        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
            statement.execute(
                "UPDATE userPlaylist SET \"imageSource\" = 'USER' WHERE \"imageId\" IS NOT NULL AND \"imageSource\" IS NULL"
            )
            statement.execute(
                "UPDATE collection SET \"imageSource\" = 'USER' WHERE \"imageId\" IS NOT NULL AND \"imageSource\" IS NULL"
            )
        }
    }
}
