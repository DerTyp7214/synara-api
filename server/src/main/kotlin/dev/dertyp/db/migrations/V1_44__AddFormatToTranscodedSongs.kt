package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.TranscodedSongTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_44__AddFormatToTranscodedSongs : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val databaseProductName = context.connection.metaData.databaseProductName
        if (databaseProductName.contains("PostgreSQL", ignoreCase = true)) {
            val tableNames = listOf("transcodedSong", "transcodedsong")
            val schema = try { context.connection.schema } catch (e: Exception) { null }

            for (tableName in tableNames) {
                val pkName = context.connection.metaData.getPrimaryKeys(null, schema, tableName).use { rs ->
                    if (rs.next()) rs.getString("PK_NAME") else null
                }
                if (pkName != null) {
                    context.connection.createStatement().use { statement ->
                        statement.execute("ALTER TABLE \"$tableName\" DROP CONSTRAINT \"$pkName\"")
                    }
                    break
                }
            }
        }

        val alterStatements = tempConnection {
            MigrationUtils.statementsRequiredForDatabaseMigration(TranscodedSongTable)
        }

        context.connection.createStatement().use { statement ->
            for (sql in alterStatements) {
                statement.execute(sql)
            }
        }
    }
}
