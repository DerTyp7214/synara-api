package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.ListenTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_61__AddUserIdToListen : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val columnStatements = tempConnection {
            SchemaUtils.addMissingColumnsStatements(ListenTable)
        }
        val indexStatements = tempConnection {
            ListenTable.indices
                .filter { index -> index.columns.singleOrNull()?.name == ListenTable.userId.name }
                .flatMap { it.createStatement() }
        }.map {
            it.replaceFirst("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
                .replaceFirst("CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX IF NOT EXISTS ")
        }

        context.connection.createStatement().use { statement ->
            for (sql in columnStatements) statement.execute(sql)
            for (sql in indexStatements) statement.execute(sql)
        }
    }
}
