package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.ArtistSourceRuleTable
import dev.dertyp.db.HiddenReleaseTable
import dev.dertyp.db.ProviderReleaseTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_95__ReleaseHidingAndSuspects : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val statements = tempConnection {
            SchemaUtils.createStatements(
                HiddenReleaseTable,
                ArtistSourceRuleTable
            ) + SchemaUtils.addMissingColumnsStatements(ProviderReleaseTable)
        }.map {
            it.replaceFirst("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
                .replaceFirst("CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX IF NOT EXISTS ")
        }

        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
