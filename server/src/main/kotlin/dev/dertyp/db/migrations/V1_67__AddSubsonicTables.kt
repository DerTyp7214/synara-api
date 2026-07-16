package dev.dertyp.db.migrations

import dev.dertyp.core.tempConnection
import dev.dertyp.db.SubsonicCredentialTable
import dev.dertyp.db.UserAlbumTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_67__AddSubsonicTables : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val statements = tempConnection {
            SchemaUtils.createStatements(SubsonicCredentialTable, UserAlbumTable)
        }
        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
