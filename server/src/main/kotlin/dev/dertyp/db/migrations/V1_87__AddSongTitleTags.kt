package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.SongTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_87__AddSongTitleTags : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)
        val statements = tempConnection {
            SchemaUtils.addMissingColumnsStatements(SongTable)
        }
        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }

        val isPostgres = context.connection.metaData.databaseProductName.lowercase().contains("postgresql")
        if (!isPostgres) return

        val trigger = """
            CREATE OR REPLACE FUNCTION trigger_on_song_change()
            RETURNS TRIGGER AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    DELETE FROM search_index_queue WHERE entity_type = 'SONG' AND entity_id = OLD.id;
                ELSE
                    IF (TG_OP = 'INSERT' OR OLD.title IS DISTINCT FROM NEW.title OR OLD.title_tags IS DISTINCT FROM NEW.title_tags OR OLD."albumId" IS DISTINCT FROM NEW."albumId") THEN
                        PERFORM queue_for_search_indexing('SONG', NEW.id);
                    END IF;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
        """.trimIndent()

        context.connection.createStatement().use { statement ->
            statement.execute(trigger)
        }
    }
}
