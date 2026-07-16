package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.ApiKeyTable
import dev.dertyp.dbQuery
import dev.dertyp.plugins.ApiKeyScope
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update

@Migration("3.6")
class PopulateApiKeyScopes : CustomMigration() {
    override suspend fun migrate() {
        val updated = dbQuery {
            ApiKeyTable.update({ ApiKeyTable.scopes eq "" }) {
                it[ApiKeyTable.scopes] = ApiKeyScope.Radio.id
            }
        }
        logger.info("Granted the '${ApiKeyScope.Radio.id}' scope to $updated existing API keys")
    }
}
