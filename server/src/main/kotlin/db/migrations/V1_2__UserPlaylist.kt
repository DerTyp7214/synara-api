package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.UserPlaylistSongTable
import dev.dertyp.db.UserPlaylistTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.sql.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_2__UserPlaylist : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val statements = tempConnection {
            SchemaUtils.createStatements(UserPlaylistTable, UserPlaylistSongTable)
        }

        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}