package dev.dertyp.db.migrations

import dev.dertyp.core.db.Dialect
import dev.dertyp.core.db.existingForeignKeyNames
import dev.dertyp.core.tempConnection
import dev.dertyp.db.SongTable
import dev.dertyp.db.SyncServiceTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_100__FixContradictoryForeignKeys : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        if (Dialect.of(connection) != Dialect.POSTGRES) return

        val columns = listOf(SongTable.albumId, SyncServiceTable.ownerId)
        val existingNames = columns.associateWith { existingForeignKeyNames(connection, it) }

        val statements = tempConnection {
            columns.flatMap { column ->
                val key = requireNotNull(column.foreignKey)
                existingNames.getValue(column).flatMap { key.copy(name = it).dropStatement() } + key.createStatement()
            }
        }

        connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
