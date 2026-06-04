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

        val alterStatements = tempConnection {
            MigrationUtils.statementsRequiredForDatabaseMigration(TranscodedSongTable)
        }

        context.connection.createStatement().use { statement ->
            if (context.connection.metaData.databaseProductName.contains("PostgreSQL", ignoreCase = true)) {
                val schema = try { context.connection.schema } catch (e: Exception) { null }
                val pkInfo = listOf("transcodedSong", "transcodedsong").firstNotNullOfOrNull { tableName ->
                    context.connection.metaData.getPrimaryKeys(null, schema, tableName).use { rs ->
                        if (rs.next()) tableName to rs.getString("PK_NAME") else null
                    }
                }

                if (pkInfo != null) {
                    val (tableName, pkName) = pkInfo
                    val escapedTable = if (tableName.any { it.isUpperCase() }) "\"$tableName\"" else tableName
                    statement.execute("ALTER TABLE $escapedTable DROP CONSTRAINT \"$pkName\"")
                }
            }

            for (sql in alterStatements) {
                statement.execute(sql)
            }
        }
    }
}
