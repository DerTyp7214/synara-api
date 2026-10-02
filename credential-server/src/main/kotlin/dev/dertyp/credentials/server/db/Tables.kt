package dev.dertyp.credentials.server.db

import dev.dertyp.credentials.CredentialKind
import dev.dertyp.credentials.CredentialStatus
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object ClientTable : UUIDTable("cs_client") {
    val clientId = varchar("clientId", 64).uniqueIndex()
    val name = varchar("name", 255)
    val secretHash = varchar("secretHash", 64)
    val tokenVersion = integer("tokenVersion").default(1)
    val enabled = bool("enabled").default(true)
    val createdAt = long("createdAt")
    val lastTokenAt = long("lastTokenAt").nullable()
}

object CredentialTable : UUIDTable("cs_credential") {
    val name = varchar("name", 128).uniqueIndex()
    val kind = enumerationByName<CredentialKind>("kind", 64)
    val description = text("description").nullable()
    val secret = text("secret")
    val fingerprint = varchar("fingerprint", 128).nullable()
    val status = enumerationByName<CredentialStatus>("status", 32)
    val statusMessage = text("statusMessage").nullable()
    val expiresAt = long("expiresAt").nullable()
    val createdAt = long("createdAt")
    val updatedAt = long("updatedAt")
}

object GrantTable : UUIDTable("cs_grant") {
    val client = reference("client", ClientTable.id, onDelete = ReferenceOption.CASCADE)
    val credential = reference("credential", CredentialTable.id, onDelete = ReferenceOption.CASCADE)
    val writeBack = bool("writeBack").default(false)
    val lastUsedAt = long("lastUsedAt").nullable()

    init {
        uniqueIndex(client, credential)
        index(false, credential)
    }
}

object SigningKeyTable : Table("cs_signing_key") {
    val kid = varchar("kid", 64)
    val privateKey = text("privateKey")
    val publicKey = text("publicKey")
    val active = bool("active").default(false)
    val createdAt = long("createdAt")

    override val primaryKey = PrimaryKey(kid)
}
