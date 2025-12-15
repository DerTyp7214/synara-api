package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.db.*
import dev.dertyp.services.DatabaseManager
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.sql.SchemaUtils
import org.koin.core.context.GlobalContext

@Suppress("unused", "ClassName")
class V1_0__InitialSchema : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val databaseManager = GlobalContext.get().get<DatabaseManager>()

        val statements = databaseManager.tempConnection {
            SchemaUtils.createStatements(
                SyncServiceTable,
                UserTable,
                SongTable,
                UserSongTable,
                SongArtistTable,
                TranscodedSongTable,
                RefreshTokenTable,
                PlaylistTable,
                PlaylistSongTable,
                ImageTable,
                ArtistTable,
                AlbumTable,
                AlbumArtistTable
            )
        }

        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}