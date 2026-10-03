package dev.dertyp.credentials.server.db

import dev.dertyp.credentials.*
import dev.dertyp.credentials.server.broker.CredentialException
import dev.dertyp.credentials.server.broker.CredentialStateUpdate
import dev.dertyp.credentials.server.broker.SecretRepository
import dev.dertyp.credentials.server.broker.StoredSecret
import dev.dertyp.credentials.server.crypto.SecretBox
import dev.dertyp.credentials.server.crypto.SecretHasher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

data class ClientRecord(
    val id: UUID,
    val clientId: String,
    val name: String,
    val secretHash: String,
    val tokenVersion: Int,
    val enabled: Boolean,
)

class CredentialStore(private val database: Database, private val box: SecretBox) : SecretRepository {

    fun createClient(name: String, grants: List<GrantSpec> = emptyList()): CreatedClient {
        val clientName = name.trim()
        if (clientName.isEmpty()) throw CredentialException(
            CredentialErrorCode.INVALID,
            "Client name must not be empty"
        )
        val secret = SecretHasher.newSecret()
        val summary = transaction(database) {
            val id = ClientTable.insertAndGetId {
                it[clientId] = SecretHasher.newClientId()
                it[ClientTable.name] = clientName
                it[secretHash] = SecretHasher.hash(secret)
                it[tokenVersion] = 1
                it[enabled] = true
                it[createdAt] = System.currentTimeMillis()
            }
            replaceGrants(id, grants)
            summaryOf(id.value)
        }
        return CreatedClient(summary, secret)
    }

    fun listClients(): List<ClientSummary> = transaction(database) {
        val grants = grantInfoByClient()
        ClientTable.selectAll().orderBy(ClientTable.createdAt to SortOrder.ASC, ClientTable.id to SortOrder.ASC)
            .map { it.toSummary(grants[it[ClientTable.id].value].orEmpty()) }
    }

    fun client(ref: String): ClientSummary? = transaction(database) {
        findClientId(ref)?.let { summaryOf(it) }
    }

    fun clientRecord(id: UUID): ClientRecord? = transaction(database) {
        ClientTable.selectAll().where { ClientTable.id eq id }.singleOrNull()?.toRecord()
    }

    fun clientRecordByClientId(clientId: String): ClientRecord? = transaction(database) {
        ClientTable.selectAll().where { ClientTable.clientId eq clientId }.singleOrNull()?.toRecord()
    }

    fun updateClient(ref: String, request: UpdateClientRequest): ClientSummary = transaction(database) {
        val id = requireClient(ref)
        val current = ClientTable.selectAll().where { ClientTable.id eq id }.single()
        val newName = request.name?.trim()
        if (newName != null && newName.isEmpty()) {
            throw CredentialException(CredentialErrorCode.INVALID, "Client name must not be empty")
        }
        val newEnabled = request.enabled
        val enabledChanged = newEnabled != null && newEnabled != current[ClientTable.enabled]
        ClientTable.update({ ClientTable.id eq id }) {
            if (newName != null) it[name] = newName
            if (newEnabled != null) it[enabled] = newEnabled
            if (enabledChanged) it[tokenVersion] = tokenVersion + 1
        }
        summaryOf(id)
    }

    fun deleteClient(ref: String): Boolean = transaction(database) {
        val id = findClientId(ref) ?: return@transaction false
        ClientTable.deleteWhere { ClientTable.id eq id } > 0
    }

    fun rotateSecret(ref: String): CreatedClient {
        val secret = SecretHasher.newSecret()
        val summary = transaction(database) {
            val id = requireClient(ref)
            ClientTable.update({ ClientTable.id eq id }) {
                it[secretHash] = SecretHasher.hash(secret)
                it[tokenVersion] = tokenVersion + 1
            }
            summaryOf(id)
        }
        return CreatedClient(summary, secret)
    }

    fun revokeTokens(ref: String): ClientSummary = transaction(database) {
        val id = requireClient(ref)
        bumpTokenVersion(listOf(id))
        summaryOf(id)
    }

    fun setGrants(ref: String, grants: List<GrantSpec>): ClientSummary = transaction(database) {
        val id = requireClient(ref)
        replaceGrants(EntityID(id, ClientTable), grants)
        bumpTokenVersion(listOf(id))
        summaryOf(id)
    }

    fun grant(ref: String, credentialName: String, writeBack: Boolean): ClientSummary = transaction(database) {
        val id = requireClient(ref)
        val current = currentGrantSpecs(id).filterNot { it.name == credentialName }
        replaceGrants(EntityID(id, ClientTable), current + GrantSpec(credentialName, writeBack))
        bumpTokenVersion(listOf(id))
        summaryOf(id)
    }

    fun ungrant(ref: String, credentialName: String): ClientSummary = transaction(database) {
        val id = requireClient(ref)
        val credentialId = credentialId(credentialName)
        if (credentialId != null) {
            val removed = GrantTable.deleteWhere { (client eq id) and (credential eq credentialId) }
            if (removed > 0) bumpTokenVersion(listOf(id))
        }
        summaryOf(id)
    }

    fun grantsOf(clientId: UUID): List<GrantInfo> = transaction(database) {
        grantInfoByClient(clientId)[clientId].orEmpty()
    }

    fun markTokenIssued(clientId: UUID) {
        transaction(database) {
            ClientTable.update({ ClientTable.id eq clientId }) { it[lastTokenAt] = System.currentTimeMillis() }
        }
    }

    fun touchGrant(clientId: UUID, credentialName: String) {
        transaction(database) {
            val credentialId = credentialId(credentialName) ?: return@transaction
            GrantTable.update({ (GrantTable.client eq clientId) and (GrantTable.credential eq credentialId) }) {
                it[lastUsedAt] = System.currentTimeMillis()
            }
        }
    }

    fun listCredentials(): List<CredentialSummary> = transaction(database) {
        val grantedTo = grantedToByCredential()
        CredentialTable.selectAll().orderBy(CredentialTable.name to SortOrder.ASC)
            .map { it.toCredentialSummary(grantedTo[it[CredentialTable.id].value].orEmpty()) }
    }

    fun credential(name: String): CredentialSummary? = transaction(database) {
        val row = CredentialTable.selectAll().where { CredentialTable.name eq name }.singleOrNull()
            ?: return@transaction null
        val id = row[CredentialTable.id].value
        row.toCredentialSummary(grantedToByCredential(id)[id].orEmpty())
    }

    fun upsertCredential(
        name: String,
        kind: CredentialKind,
        description: String?,
        secret: StoredSecret,
        state: CredentialStateUpdate,
    ): CredentialSummary {
        if (!CredentialNames.isValid(name)) throw CredentialException(
            CredentialErrorCode.INVALID,
            "Invalid credential name $name"
        )
        val sealed = seal(name, secret)
        val now = System.currentTimeMillis()
        transaction(database) {
            val existing = CredentialTable.selectAll().where { CredentialTable.name eq name }.singleOrNull()
            if (existing == null) {
                CredentialTable.insert {
                    it[CredentialTable.name] = name
                    it[CredentialTable.kind] = kind
                    it[CredentialTable.description] = description
                    it[CredentialTable.secret] = sealed
                    it.applyState(state)
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            } else {
                CredentialTable.update({ CredentialTable.name eq name }) {
                    it[CredentialTable.kind] = kind
                    if (description != null) it[CredentialTable.description] = description
                    it[CredentialTable.secret] = sealed
                    it.applyState(state)
                    it[updatedAt] = now
                }
            }
        }
        return credential(name)!!
    }

    fun deleteCredential(name: String): Boolean = transaction(database) {
        val id = credentialId(name) ?: return@transaction false
        val holders = GrantTable.select(GrantTable.client).where { GrantTable.credential eq id }
            .map { it[GrantTable.client].value }
        CredentialTable.deleteWhere { CredentialTable.id eq id }
        bumpTokenVersion(holders)
        true
    }

    override fun kind(name: String): CredentialKind? = transaction(database) {
        CredentialTable.select(CredentialTable.kind).where { CredentialTable.name eq name }
            .singleOrNull()?.get(CredentialTable.kind)
    }

    override fun loadSecret(name: String): StoredSecret? {
        val sealed = transaction(database) {
            CredentialTable.select(CredentialTable.secret).where { CredentialTable.name eq name }
                .singleOrNull()?.get(CredentialTable.secret)
        } ?: return null
        val plain = box.decrypt(aad(name), sealed)
        return CredentialJson.json.decodeFromString(StoredSecret.serializer(), plain)
    }

    override fun saveSecret(name: String, secret: StoredSecret, state: CredentialStateUpdate) {
        upsertCredential(name, secret.kind, null, secret, state)
    }

    override fun updateState(name: String, state: CredentialStateUpdate) {
        transaction(database) {
            CredentialTable.update({ CredentialTable.name eq name }) {
                it.applyState(state)
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    private fun seal(name: String, secret: StoredSecret): String =
        box.encrypt(aad(name), CredentialJson.json.encodeToString(StoredSecret.serializer(), secret))

    private fun aad(name: String) = "credential:$name"

    private fun UpdateBuilder<*>.applyState(state: CredentialStateUpdate) {
        this[CredentialTable.status] = state.status
        this[CredentialTable.statusMessage] = state.statusMessage
        this[CredentialTable.expiresAt] = state.expiresAt
        this[CredentialTable.fingerprint] = state.fingerprint
    }

    private fun findClientId(ref: String): UUID? {
        val uuid = runCatching { UUID.fromString(ref) }.getOrNull()
        val query = if (uuid != null) {
            ClientTable.select(ClientTable.id).where { ClientTable.id eq uuid }
        } else {
            ClientTable.select(ClientTable.id).where { ClientTable.clientId eq ref }
        }
        return query.singleOrNull()?.get(ClientTable.id)?.value
    }

    private fun requireClient(ref: String): UUID =
        findClientId(ref) ?: throw CredentialException(CredentialErrorCode.NOT_FOUND, "Client $ref does not exist")

    private fun credentialId(name: String): UUID? =
        CredentialTable.select(CredentialTable.id).where { CredentialTable.name eq name }
            .singleOrNull()?.get(CredentialTable.id)?.value

    private fun bumpTokenVersion(clientIds: Collection<UUID>) {
        if (clientIds.isEmpty()) return
        ClientTable.update({ ClientTable.id inList clientIds }) {
            it[tokenVersion] = tokenVersion + 1
        }
    }

    private fun currentGrantSpecs(clientId: UUID): List<GrantSpec> =
        (GrantTable innerJoin CredentialTable)
            .select(CredentialTable.name, GrantTable.writeBack)
            .where { GrantTable.client eq clientId }
            .map { GrantSpec(it[CredentialTable.name], it[GrantTable.writeBack]) }

    private fun replaceGrants(clientId: EntityID<UUID>, grants: List<GrantSpec>) {
        val merged = grants.groupBy { it.name }.map { (name, specs) -> GrantSpec(name, specs.any { it.writeBack }) }
        val ids = if (merged.isEmpty()) {
            emptyMap()
        } else {
            CredentialTable.select(CredentialTable.id, CredentialTable.name)
                .where { CredentialTable.name inList merged.map { it.name } }
                .associate { it[CredentialTable.name] to it[CredentialTable.id] }
        }
        val missing = merged.map { it.name }.filterNot { it in ids }
        if (missing.isNotEmpty()) {
            throw CredentialException(CredentialErrorCode.NOT_FOUND, "Unknown credential(s): ${missing.joinToString()}")
        }
        GrantTable.deleteWhere { client eq clientId }
        merged.forEach { spec ->
            GrantTable.insert {
                it[client] = clientId
                it[credential] = ids.getValue(spec.name)
                it[writeBack] = spec.writeBack
            }
        }
    }

    private fun grantInfoByClient(clientId: UUID? = null): Map<UUID, List<GrantInfo>> {
        val query = (GrantTable innerJoin CredentialTable)
            .select(GrantTable.client, CredentialTable.name, CredentialTable.kind, GrantTable.writeBack)
        if (clientId != null) query.where { GrantTable.client eq clientId }
        return query.orderBy(CredentialTable.name to SortOrder.ASC)
            .groupBy({ it[GrantTable.client].value }) {
                GrantInfo(it[CredentialTable.name], it[CredentialTable.kind], it[GrantTable.writeBack])
            }
    }

    private fun grantedToByCredential(credentialId: UUID? = null): Map<UUID, List<String>> {
        val query = (GrantTable innerJoin ClientTable).select(GrantTable.credential, ClientTable.clientId)
        if (credentialId != null) query.where { GrantTable.credential eq credentialId }
        return query.orderBy(ClientTable.clientId to SortOrder.ASC)
            .groupBy({ it[GrantTable.credential].value }) { it[ClientTable.clientId] }
    }

    private fun summaryOf(id: UUID): ClientSummary {
        val row = ClientTable.selectAll().where { ClientTable.id eq id }.single()
        return row.toSummary(grantInfoByClient(id)[id].orEmpty())
    }

    private fun ResultRow.toSummary(grants: List<GrantInfo>) = ClientSummary(
        id = this[ClientTable.id].value.toString(),
        clientId = this[ClientTable.clientId],
        name = this[ClientTable.name],
        enabled = this[ClientTable.enabled],
        tokenVersion = this[ClientTable.tokenVersion],
        createdAt = this[ClientTable.createdAt],
        lastTokenAt = this[ClientTable.lastTokenAt],
        grants = grants,
    )

    private fun ResultRow.toRecord() = ClientRecord(
        id = this[ClientTable.id].value,
        clientId = this[ClientTable.clientId],
        name = this[ClientTable.name],
        secretHash = this[ClientTable.secretHash],
        tokenVersion = this[ClientTable.tokenVersion],
        enabled = this[ClientTable.enabled],
    )

    private fun ResultRow.toCredentialSummary(grantedTo: List<String>) = CredentialSummary(
        name = this[CredentialTable.name],
        kind = this[CredentialTable.kind],
        description = this[CredentialTable.description],
        status = this[CredentialTable.status],
        statusMessage = this[CredentialTable.statusMessage],
        expiresAt = this[CredentialTable.expiresAt],
        updatedAt = this[CredentialTable.updatedAt],
        grantedTo = grantedTo,
    )
}
