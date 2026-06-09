package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.SearchIndexQueueTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName")
class V1_47__AddFTSIndexQueue : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val isPostgres = context.connection.metaData.databaseProductName.lowercase().contains("postgresql")
        if (!isPostgres) return

        val tableStatements = tempConnection {
            SchemaUtils.createStatements(SearchIndexQueueTable)
        }

        val alterStatements = listOf(
            "ALTER TABLE song ADD COLUMN IF NOT EXISTS search_vector tsvector",
            "ALTER TABLE album ADD COLUMN IF NOT EXISTS search_vector tsvector",
            "ALTER TABLE artist ADD COLUMN IF NOT EXISTS search_vector tsvector",
            "CREATE INDEX IF NOT EXISTS song_search_vector_idx ON song USING GIN (search_vector)",
            "CREATE INDEX IF NOT EXISTS album_search_vector_idx ON album USING GIN (search_vector)",
            "CREATE INDEX IF NOT EXISTS artist_search_vector_idx ON artist USING GIN (search_vector)"
        )

        context.connection.createStatement().use { statement ->
            for (sql in tableStatements) {
                statement.execute(sql)
            }
            for (sql in alterStatements) {
                statement.execute(sql)
            }
        }
    }
}
