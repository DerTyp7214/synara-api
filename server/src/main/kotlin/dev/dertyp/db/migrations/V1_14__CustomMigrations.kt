package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.CustomMigrationTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_14__CustomMigrations : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val statements = tempConnection {
            SchemaUtils.createStatements(CustomMigrationTable)
        }
        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
