package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.RpcCallEventTable
import dev.dertyp.db.RpcCallStatsTable
import dev.dertyp.db.RpcCallTotalsTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_55__RpcCallMetrics : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val statements = tempConnection {
            SchemaUtils.createStatements(RpcCallTotalsTable, RpcCallStatsTable, RpcCallEventTable)
        }
        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
