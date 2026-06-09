package dev.dertyp.db.migrations

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

@Suppress("unused", "ClassName")
class V1_46__AddFTSIndices : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val isPostgres = context.connection.metaData.databaseProductName.lowercase().contains("postgresql")
        if (!isPostgres) return

        val statements = listOf(
            "CREATE INDEX IF NOT EXISTS song_title_fts_idx ON song USING GIN (to_tsvector('simple', coalesce(title, '')))",
            "CREATE INDEX IF NOT EXISTS song_lyrics_fts_idx ON song USING GIN (to_tsvector('simple', coalesce(lyrics, '')))",
            "CREATE INDEX IF NOT EXISTS artist_name_fts_idx ON artist USING GIN (to_tsvector('simple', coalesce(name, '')))",
            "CREATE INDEX IF NOT EXISTS album_name_fts_idx ON album USING GIN (to_tsvector('simple', coalesce(name, '')))",
            "CREATE INDEX IF NOT EXISTS artist_alias_name_fts_idx ON artistAlias USING GIN (to_tsvector('simple', coalesce(name, '')))",
            "CREATE INDEX IF NOT EXISTS playlist_name_fts_idx ON playlist USING GIN (to_tsvector('simple', coalesce(name, '')))",
            "CREATE INDEX IF NOT EXISTS user_playlist_name_fts_idx ON userPlaylist USING GIN (to_tsvector('simple', coalesce(name, '')))",
            "CREATE INDEX IF NOT EXISTS mb_recording_title_fts_idx ON mb_recording USING GIN (to_tsvector('simple', coalesce(title, '')))",
            "CREATE INDEX IF NOT EXISTS mb_release_title_fts_idx ON mb_release USING GIN (to_tsvector('simple', coalesce(title, '')))",
            "CREATE INDEX IF NOT EXISTS mb_release_disambig_fts_idx ON mb_release USING GIN (to_tsvector('simple', coalesce(disambiguation, '')))",
            "CREATE INDEX IF NOT EXISTS mb_artist_name_fts_idx ON mb_artist USING GIN (to_tsvector('simple', coalesce(name, '')))",
            "CREATE INDEX IF NOT EXISTS mb_artist_disambig_fts_idx ON mb_artist USING GIN (to_tsvector('simple', coalesce(disambiguation, '')))",
            "CREATE INDEX IF NOT EXISTS mb_artist_alias_name_fts_idx ON mb_artist_alias USING GIN (to_tsvector('simple', coalesce(name, '')))",
            "CREATE INDEX IF NOT EXISTS synced_lyrics_raw_fts_idx ON synced_lyrics USING GIN (to_tsvector('simple', coalesce(raw_lyrics, '')))",
            "CREATE INDEX IF NOT EXISTS song_mbid_fts_idx ON song_musicbrainz USING GIN (to_tsvector('simple', coalesce(CAST(\"musicBrainzId\" AS VARCHAR(36)), '')))"
        )

        context.connection.createStatement().use { statement ->
            for (sql in statements) {
                statement.execute(sql)
            }
        }
    }
}
