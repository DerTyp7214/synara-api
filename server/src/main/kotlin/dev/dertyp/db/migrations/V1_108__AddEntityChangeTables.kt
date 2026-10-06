package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.CollectionAlbumTable
import dev.dertyp.db.CollectionArtistTable
import dev.dertyp.db.CollectionPlaylistTable
import dev.dertyp.db.CollectionSongTable
import dev.dertyp.db.EntityChangeScopeTable
import dev.dertyp.db.EntityChangeTable
import dev.dertyp.db.EntityChangeTrackingTable
import dev.dertyp.db.PlaylistSongTable
import dev.dertyp.db.UserEntityChangeTable
import dev.dertyp.db.UserPlaylistSongTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

class V1_108__AddEntityChangeTables : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val memberColumns = listOf(
            UserPlaylistSongTable.songId,
            PlaylistSongTable.songId,
            CollectionSongTable.songId,
            CollectionAlbumTable.albumId,
            CollectionArtistTable.artistId,
            CollectionPlaylistTable.playlistId,
        )

        val statements = tempConnection {
            SchemaUtils.createStatements(
                EntityChangeTable,
                UserEntityChangeTable,
                EntityChangeScopeTable,
                EntityChangeTrackingTable
            ) + memberColumns.flatMap { column ->
                column.table.indices
                    .single { index -> !index.unique && index.columns == listOf(column) }
                    .createStatement()
            }
        }.map {
            it.replaceFirst("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
                .replaceFirst("CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX IF NOT EXISTS ")
        }

        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
