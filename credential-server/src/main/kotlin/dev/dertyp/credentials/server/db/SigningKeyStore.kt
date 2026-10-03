package dev.dertyp.credentials.server.db

import dev.dertyp.credentials.server.crypto.SecretBox
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

data class StoredSigningKey(
    val kid: String,
    val privateKeyPkcs8: String?,
    val publicKeyX509: String,
    val active: Boolean,
    val createdAt: Long,
)

class SigningKeyStore(private val database: Database, private val box: SecretBox) {

    fun active(): StoredSigningKey? = transaction(database) {
        SigningKeyTable.selectAll().where { SigningKeyTable.active eq true }
            .orderBy(SigningKeyTable.createdAt to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toKey(withPrivate = true)
    }

    fun find(kid: String): StoredSigningKey? = transaction(database) {
        SigningKeyTable.selectAll().where { SigningKeyTable.kid eq kid }.singleOrNull()?.toKey(withPrivate = false)
    }

    fun list(): List<StoredSigningKey> = transaction(database) {
        SigningKeyTable.selectAll().orderBy(SigningKeyTable.createdAt to SortOrder.ASC)
            .map { it.toKey(withPrivate = false) }
    }

    fun activate(kid: String, privateKeyPkcs8: String, publicKeyX509: String) {
        val sealed = box.encrypt(aad(kid), privateKeyPkcs8)
        transaction(database) {
            SigningKeyTable.update({ SigningKeyTable.active eq true }) { it[active] = false }
            SigningKeyTable.insert {
                it[SigningKeyTable.kid] = kid
                it[privateKey] = sealed
                it[publicKey] = publicKeyX509
                it[active] = true
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }

    private fun aad(kid: String) = "signing-key:$kid"

    private fun ResultRow.toKey(withPrivate: Boolean): StoredSigningKey {
        val kid = this[SigningKeyTable.kid]
        return StoredSigningKey(
            kid = kid,
            privateKeyPkcs8 = if (withPrivate) box.decrypt(aad(kid), this[SigningKeyTable.privateKey]) else null,
            publicKeyX509 = this[SigningKeyTable.publicKey],
            active = this[SigningKeyTable.active],
            createdAt = this[SigningKeyTable.createdAt],
        )
    }
}
