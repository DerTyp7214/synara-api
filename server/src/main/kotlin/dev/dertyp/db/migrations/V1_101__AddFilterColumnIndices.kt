package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.*
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.Column

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_101__AddFilterColumnIndices : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val indexColumns: List<List<Column<*>>> = listOf(
            listOf(SongTable.albumId),
            listOf(SongTable.filePath),
            listOf(SongTable.isrc),
            listOf(SongTable.originalUrl),
            listOf(SongArtistTable.artistId),
            listOf(AlbumArtistTable.artistId),
            listOf(ArtistAliasTable.artistId),
            listOf(ArtistMemberTable.groupId),
            listOf(SongGenreTable.genreId),
            listOf(SongMusicBrainzTable.musicBrainzId),
            listOf(AlbumTable.originalId),
            listOf(SessionTable.userId),
            listOf(RefreshTokenTable.userId),
            listOf(SongProviderTable.externalId, SongProviderTable.provider),
            listOf(SongProviderTable.rawUrl),
            listOf(AlbumProviderTable.externalId, AlbumProviderTable.provider),
            listOf(AlbumProviderTable.rawUrl),
            listOf(MBRelationProviderTable.externalId, MBRelationProviderTable.provider),
            listOf(MBRelationProviderTable.rawUrl),
        )

        val statements = tempConnection {
            indexColumns.flatMap { columns ->
                columns.first().table.indices
                    .single { index -> !index.unique && index.columns == columns }
                    .createStatement()
            }
        }.map {
            it.replaceFirst("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
        }

        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
