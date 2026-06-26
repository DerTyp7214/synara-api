package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.MBRelationProviderTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_52__AddMBRelationProviderTable : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val statements = tempConnection {
            SchemaUtils.createStatements(
                MBRelationProviderTable
            )
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
