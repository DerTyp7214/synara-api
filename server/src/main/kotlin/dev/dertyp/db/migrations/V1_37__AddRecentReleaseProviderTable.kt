package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_37__AddRecentReleaseProviderTable : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val uuidType = if (context.connection.metaData.driverName.contains("sqlite", ignoreCase = true)) {
            "BINARY(16)"
        } else "uuid"

        val statement = """
            CREATE TABLE IF NOT EXISTS recent_release_provider (
                "releaseId" $uuidType NOT NULL,
                provider VARCHAR(64) NOT NULL,
                "externalId" TEXT DEFAULT '' NOT NULL,
                type VARCHAR(32) NULL,
                "rawUrl" TEXT NOT NULL,
                "addedAt" BIGINT NOT NULL,
                CONSTRAINT pk_recent_release_provider PRIMARY KEY ("releaseId", provider, "externalId")
            )
        """.trimIndent()

        context.connection.createStatement().use {
            try {
                it.execute(statement)
            } catch (e: Exception) {
                if (e.message?.contains("already exists", ignoreCase = true) != true) {
                    throw e
                }
            }
        }
    }
}
