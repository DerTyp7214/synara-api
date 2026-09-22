package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

@Migration("3.20")
class ResetUnmatchedProviderReleaseResolves : CustomMigration() {

    override suspend fun migrate() {
        logTask("Reset Unmatched Provider Release Resolves") {
            val count = dbQuery {
                ProviderReleaseTable.update({
                    ProviderReleaseTable.releaseGroupId.isNull() and ProviderReleaseTable.linksResolvedAt.isNotNull()
                }) {
                    it[ProviderReleaseTable.linksResolvedAt] = null
                }
            }
            log("Reset $count unmatched provider release resolves")
            updateProgress(100.0, "Reset $count unmatched provider release resolves")

            mapOf("reset" to count)
        }
    }
}
