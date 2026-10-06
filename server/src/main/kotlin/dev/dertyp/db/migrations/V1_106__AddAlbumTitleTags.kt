package dev.dertyp.db.migrations

import dev.dertyp.core.db.Dialect
import dev.dertyp.core.tempConnection
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.AlbumTitleTagTable
import dev.dertyp.db.AlbumVersionGroupTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

class V1_106__AddAlbumTitleTags : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val statements = tempConnection {
            SchemaUtils.createStatements(AlbumVersionGroupTable) +
                    SchemaUtils.addMissingColumnsStatements(AlbumTable) +
                    SchemaUtils.createStatements(AlbumTitleTagTable)
        }.map {
            it.replaceFirst("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
                .replaceFirst("CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX IF NOT EXISTS ")
        }

        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }

        val isPostgres = Dialect.of(context.connection) == Dialect.POSTGRES
        if (!isPostgres) return

        val trigger = """
            CREATE OR REPLACE FUNCTION trigger_on_album_change()
            RETURNS TRIGGER AS $$
            BEGIN
                IF (TG_OP = 'DELETE') THEN
                    PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = OLD.id;
                ELSE
                    IF (TG_OP = 'INSERT' OR OLD.name IS DISTINCT FROM NEW.name OR OLD.title_tags IS DISTINCT FROM NEW.title_tags) THEN
                        PERFORM queue_for_search_indexing('ALBUM', NEW.id);
                        PERFORM queue_for_search_indexing('SONG', s.id) FROM song s WHERE s."albumId" = NEW.id;
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
