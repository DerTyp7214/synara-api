package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.db.dbQuery
import dev.dertyp.db.ApiKeyTable
import dev.dertyp.services.credentials.CredentialCipher
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject

@Migration("3.27")
class EncryptApiKeys : CustomMigration() {
    private val cipher by inject<CredentialCipher>()

    override suspend fun migrate() {
        var encrypted = 0

        dbQuery {
            val plainRows = ApiKeyTable.select(ApiKeyTable.id, ApiKeyTable.keyHash, ApiKeyTable.rawKey)
                .where { ApiKeyTable.rawKey.isNotNull() }
                .mapNotNull { row ->
                    val raw = row[ApiKeyTable.rawKey] ?: return@mapNotNull null
                    if (cipher.isEncrypted(raw)) return@mapNotNull null
                    Triple(row[ApiKeyTable.id], row[ApiKeyTable.keyHash], raw)
                }

            for ((id, keyHash, raw) in plainRows) {
                ApiKeyTable.update({ ApiKeyTable.id eq id }) {
                    it[rawKey] = cipher.encrypt(keyHash, raw)
                }
                encrypted++
            }
        }

        logger.info("API keys: encrypted $encrypted stored key(s)")
    }
}
