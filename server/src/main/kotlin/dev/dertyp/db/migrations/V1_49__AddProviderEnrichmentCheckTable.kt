package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.ProviderEnrichmentCheckTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName")
class V1_49__AddProviderEnrichmentCheckTable : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val statements = tempConnection {
            SchemaUtils.createStatements(ProviderEnrichmentCheckTable)
        }
        context.connection.createStatement().use { statement ->
            for (sql in statements) {
                try {
                    statement.execute(sql)
                } catch (e: Exception) {
                    if (e.message?.contains("already exists", ignoreCase = true) != true) {
                        throw e
                    }
                }
            }
        }
    }
}
