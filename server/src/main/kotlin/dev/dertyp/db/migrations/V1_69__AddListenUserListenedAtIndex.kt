package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.ListenTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_69__AddListenUserListenedAtIndex : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val indexStatements = tempConnection {
            ListenTable.indices
                .filter { index -> index.columns.map { it.name } == listOf(ListenTable.userId.name, ListenTable.listenedAt.name) }
                .flatMap { it.createStatement() }
        }.map {
            it.replaceFirst("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
        }

        context.connection.createStatement().use { statement ->
            for (sql in indexStatements) statement.execute(sql)
        }
    }
}
