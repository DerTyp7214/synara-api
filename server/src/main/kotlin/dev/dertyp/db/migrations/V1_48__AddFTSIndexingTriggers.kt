package dev.dertyp.db.migrations

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

@Suppress("unused", "ClassName")
class V1_48__AddFTSIndexingTriggers : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val isPostgres = context.connection.metaData.databaseProductName.lowercase().contains("postgresql")
        if (!isPostgres) return

        val statements = listOf(
            // 0. Helper function
            """
            CREATE OR REPLACE FUNCTION queue_for_search_indexing(p_entity_type VARCHAR, p_entity_id UUID)
            RETURNS VOID AS $$
            BEGIN
                INSERT INTO search_index_queue (entity_type, entity_id)
                VALUES (p_entity_type, p_entity_id)
                ON CONFLICT (entity_type, entity_id) DO NOTHING;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent(),

            // 1. Artist Table trigger
            """
            CREATE OR REPLACE FUNCTION trigger_on_artist_change()
            RETURNS TRIGGER AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    PERFORM queue_for_search_indexing('SONG', sa."songId") FROM songartist sa WHERE sa."artistId" = OLD.id;
                    PERFORM queue_for_search_indexing('ALBUM', aa."albumId") FROM albumartist aa WHERE aa."artistId" = OLD.id;
                ELSE
                    IF (TG_OP = 'INSERT' OR OLD.name IS DISTINCT FROM NEW.name) THEN
                        PERFORM queue_for_search_indexing('ARTIST', NEW.id);
                        PERFORM queue_for_search_indexing('SONG', sa."songId") FROM songartist sa WHERE sa."artistId" = NEW.id;
                        PERFORM queue_for_search_indexing('ALBUM', aa."albumId") FROM albumartist aa WHERE aa."artistId" = NEW.id;
                    END IF;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent(),
            
            "DROP TRIGGER IF EXISTS artist_change_indexing_trigger ON artist",
            "CREATE TRIGGER artist_change_indexing_trigger AFTER INSERT OR UPDATE OR DELETE ON artist FOR EACH ROW EXECUTE FUNCTION trigger_on_artist_change()",

            // 2. Album Table trigger
            """
            CREATE OR REPLACE FUNCTION trigger_on_album_change()
            RETURNS TRIGGER AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = OLD.id;
                ELSE
                    IF (TG_OP = 'INSERT' OR OLD.name IS DISTINCT FROM NEW.name) THEN
                        PERFORM queue_for_search_indexing('ALBUM', NEW.id);
                        PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = NEW.id;
                    END IF;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent(),

            "DROP TRIGGER IF EXISTS album_change_indexing_trigger ON album",
            "CREATE TRIGGER album_change_indexing_trigger AFTER INSERT OR UPDATE OR DELETE ON album FOR EACH ROW EXECUTE FUNCTION trigger_on_album_change()",

            // 3. Song Table trigger
            """
            CREATE OR REPLACE FUNCTION trigger_on_song_change()
            RETURNS TRIGGER AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    DELETE FROM search_index_queue WHERE entity_type = 'SONG' AND entity_id = OLD.id;
                ELSE
                    IF (TG_OP = 'INSERT' OR OLD.title IS DISTINCT FROM NEW.title OR OLD."albumId" IS DISTINCT FROM NEW."albumId") THEN
                        PERFORM queue_for_search_indexing('SONG', NEW.id);
                    END IF;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent(),

            "DROP TRIGGER IF EXISTS song_change_indexing_trigger ON song",
            "CREATE TRIGGER song_change_indexing_trigger AFTER INSERT OR UPDATE OR DELETE ON song FOR EACH ROW EXECUTE FUNCTION trigger_on_song_change()",

            // 4. SongArtist Table trigger
            """
            CREATE OR REPLACE FUNCTION trigger_on_song_artist_change()
            RETURNS TRIGGER AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    PERFORM queue_for_search_indexing('SONG', OLD."songId");
                ELSE
                    PERFORM queue_for_search_indexing('SONG', NEW."songId");
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent(),

            "DROP TRIGGER IF EXISTS song_artist_change_indexing_trigger ON songartist",
            "CREATE TRIGGER song_artist_change_indexing_trigger AFTER INSERT OR UPDATE OR DELETE ON songartist FOR EACH ROW EXECUTE FUNCTION trigger_on_song_artist_change()",

            // 5. AlbumArtist Table trigger
            """
            CREATE OR REPLACE FUNCTION trigger_on_album_artist_change()
            RETURNS TRIGGER AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    PERFORM queue_for_search_indexing('ALBUM', OLD."albumId");
                    PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = OLD."albumId";
                ELSE
                    PERFORM queue_for_search_indexing('ALBUM', NEW."albumId");
                    PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = NEW."albumId";
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent(),

            "DROP TRIGGER IF EXISTS album_artist_change_indexing_trigger ON albumartist",
            "CREATE TRIGGER album_artist_change_indexing_trigger AFTER INSERT OR UPDATE OR DELETE ON albumartist FOR EACH ROW EXECUTE FUNCTION trigger_on_album_artist_change()",

            // 6. ArtistAlias Table trigger
            """
            CREATE OR REPLACE FUNCTION trigger_on_artist_alias_change()
            RETURNS TRIGGER AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    PERFORM queue_for_search_indexing('ARTIST', OLD."artistId");
                    PERFORM queue_for_search_indexing('SONG', sa."songId") FROM songartist sa WHERE sa."artistId" = OLD."artistId";
                    PERFORM queue_for_search_indexing('ALBUM', aa."albumId") FROM albumartist aa WHERE aa."artistId" = OLD."artistId";
                ELSE
                    PERFORM queue_for_search_indexing('ARTIST', NEW."artistId");
                    PERFORM queue_for_search_indexing('SONG', sa."songId") FROM songartist sa WHERE sa."artistId" = NEW."artistId";
                    PERFORM queue_for_search_indexing('ALBUM', aa."albumId") FROM albumartist aa WHERE aa."artistId" = NEW."artistId";
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent(),

            "DROP TRIGGER IF EXISTS artist_alias_change_indexing_trigger ON artistalias",
            "CREATE TRIGGER artist_alias_change_indexing_trigger AFTER INSERT OR UPDATE OR DELETE ON artistalias FOR EACH ROW EXECUTE FUNCTION trigger_on_artist_alias_change()",

            // 7. Song MusicBrainz Table trigger
            """
            CREATE OR REPLACE FUNCTION trigger_on_song_mb_change()
            RETURNS TRIGGER AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    PERFORM queue_for_search_indexing('SONG', OLD."songId");
                ELSE
                    PERFORM queue_for_search_indexing('SONG', NEW."songId");
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent(),

            "DROP TRIGGER IF EXISTS song_mb_change_indexing_trigger ON song_musicbrainz",
            "CREATE TRIGGER song_mb_change_indexing_trigger AFTER INSERT OR UPDATE OR DELETE ON song_musicbrainz FOR EACH ROW EXECUTE FUNCTION trigger_on_song_mb_change()",

            // 8. Album MusicBrainz Table trigger
            """
            CREATE OR REPLACE FUNCTION trigger_on_album_mb_change()
            RETURNS TRIGGER AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    PERFORM queue_for_search_indexing('ALBUM', OLD."albumId");
                    PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = OLD."albumId";
                ELSE
                    PERFORM queue_for_search_indexing('ALBUM', NEW."albumId");
                    PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = NEW."albumId";
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent(),

            "DROP TRIGGER IF EXISTS album_mb_change_indexing_trigger ON album_musicbrainz",
            "CREATE TRIGGER album_mb_change_indexing_trigger AFTER INSERT OR UPDATE OR DELETE ON album_musicbrainz FOR EACH ROW EXECUTE FUNCTION trigger_on_album_mb_change()",

            // 9. Artist MusicBrainz Table trigger
            """
            CREATE OR REPLACE FUNCTION trigger_on_artist_mb_change()
            RETURNS TRIGGER AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    PERFORM queue_for_search_indexing('ARTIST', OLD."artistId");
                    PERFORM queue_for_search_indexing('SONG', sa."songId") FROM songartist sa WHERE sa."artistId" = OLD."artistId";
                    PERFORM queue_for_search_indexing('ALBUM', aa."albumId") FROM albumartist aa WHERE aa."artistId" = OLD."artistId";
                ELSE
                    PERFORM queue_for_search_indexing('ARTIST', NEW."artistId");
                    PERFORM queue_for_search_indexing('SONG', sa."songId") FROM songartist sa WHERE sa."artistId" = NEW."artistId";
                    PERFORM queue_for_search_indexing('ALBUM', aa."albumId") FROM albumartist aa WHERE aa."artistId" = NEW."artistId";
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent(),

            "DROP TRIGGER IF EXISTS artist_mb_change_indexing_trigger ON artist_musicbrainz",
            "CREATE TRIGGER artist_mb_change_indexing_trigger AFTER INSERT OR UPDATE OR DELETE ON artist_musicbrainz FOR EACH ROW EXECUTE FUNCTION trigger_on_artist_mb_change()"
        )

        context.connection.createStatement().use { statement ->
            for (sql in statements) {
                statement.execute(sql)
            }
        }
    }
}
