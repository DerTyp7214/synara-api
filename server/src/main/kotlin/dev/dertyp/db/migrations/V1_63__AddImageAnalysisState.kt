package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.ImageTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_63__AddImageAnalysisState : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val statements = tempConnection {
            SchemaUtils.addMissingColumnsStatements(ImageTable)
        }
        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
