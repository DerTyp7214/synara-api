package dev.dertyp.services

import dev.dertyp.core.sha256
import dev.dertyp.data.ApiKeyInfo
import dev.dertyp.data.User
import dev.dertyp.db.ApiKeyTable
import dev.dertyp.dbQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject
import java.security.SecureRandom
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class ApiKeyService : Service() {
    private val userService by inject<UserService>()
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun generateRawKey(): String {
        val random = SecureRandom.getInstanceStrong()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return "synara_" + Base64.UrlSafe.encode(bytes).trimEnd('=')
    }

    private fun hash(rawKey: String): String = rawKey.toByteArray().sha256()

    private fun map(row: ResultRow) = ApiKeyInfo(
        id = row[ApiKeyTable.id].value,
        label = row[ApiKeyTable.label],
        createdAt = row[ApiKeyTable.createdAt],
        lastUsed = row[ApiKeyTable.lastUsed],
        expiresAt = row[ApiKeyTable.expiresAt],
        isRevoked = row[ApiKeyTable.isRevoked],
    )

    suspend fun createKey(userId: UUID, label: String): String {
        val raw = generateRawKey()
        val keyHash = hash(raw)
        dbQuery {
            ApiKeyTable.insert {
                it[ApiKeyTable.keyHash] = keyHash
                it[ApiKeyTable.userId] = userId
                it[ApiKeyTable.label] = label
            }
        }
        return raw
    }

    suspend fun resolveUser(rawKey: String): User? {
        val keyHash = hash(rawKey)
        val now = System.currentTimeMillis()
        val row = dbQuery {
            ApiKeyTable.selectAll()
                .where { ApiKeyTable.keyHash eq keyHash }
                .singleOrNull()
        } ?: return null

        if (row[ApiKeyTable.isRevoked]) return null
        val expiresAt = row[ApiKeyTable.expiresAt]
        if (expiresAt != null && expiresAt <= now) return null

        val id = row[ApiKeyTable.id].value
        scope.launch {
            dbQuery { ApiKeyTable.update({ ApiKeyTable.id eq id }) { it[lastUsed] = now } }
        }
        return userService.findUserById(row[ApiKeyTable.userId].value)
    }

    suspend fun listKeys(userId: UUID): List<ApiKeyInfo> = dbQuery {
        ApiKeyTable.selectAll()
            .where { ApiKeyTable.userId eq userId }
            .orderBy(ApiKeyTable.createdAt)
            .map(::map)
    }

    suspend fun revokeKey(id: UUID, userId: UUID): Boolean = dbQuery {
        ApiKeyTable.update({ (ApiKeyTable.id eq id) and (ApiKeyTable.userId eq userId) }) {
            it[isRevoked] = true
        } > 0
    }
}
