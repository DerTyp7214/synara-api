package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.AlbumTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata

class V1_109__AddAlbumReleaseDateEstimated : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val column = AlbumTable.releaseDateEstimated
        val statements = tempConnection {
            val existing = currentDialectMetadata.tableColumns(AlbumTable)[AlbumTable].orEmpty()
            if (existing.any { it.name.equals(column.name, ignoreCase = true) }) emptyList() else column.createStatement()
        }

        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
