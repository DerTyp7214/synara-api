package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.UserCapabilityTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_32__AddUserCapabilities : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val statements = tempConnection {
            SchemaUtils.createStatements(UserCapabilityTable)
        }

        try {
            context.connection.createStatement().use { statement ->
                for (sql in statements) {
                    statement.execute(sql)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
