package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.ListenTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update

@Migration("3.10")
class BackfillListenUpdatedAt : CustomMigration() {
    override suspend fun migrate() {
        logTask("Backfill listen updatedAt") {
            val updated = dbQuery {
                ListenTable.update({ ListenTable.updatedAt eq 0L }) {
                    it[updatedAt] = listenedAt
                }
            }
            updateProgress(1.0, "Backfilled updatedAt for $updated listen(s)")
            mapOf("listensUpdated" to updated)
        }
    }
}
