package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.ListenTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_59__StoreAllListens : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection.createStatement().use { it.execute("DROP TABLE IF EXISTS listen") }

        val statements = tempConnection {
            SchemaUtils.createStatements(ListenTable)
        }
        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }

        context.connection.createStatement().use { it.execute("""UPDATE listenbrainz_user SET "lastListenedAt" = NULL""") }
    }
}
