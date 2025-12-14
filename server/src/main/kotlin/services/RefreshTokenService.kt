package dev.dertyp.services

import dev.dertyp.core.date
import dev.dertyp.core.plus
import dev.dertyp.data.RefreshToken
import dev.dertyp.db.RefreshTokenTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.sql.*
import java.time.Instant
import java.util.*
import kotlin.time.Duration

class RefreshTokenService : Service() {
    companion object {
        fun mapRefreshToken(row: ResultRow): RefreshToken {
            return RefreshToken(
                id = row[RefreshTokenTable.id].value,
                userId = row[RefreshTokenTable.userId].value,
                tokenHash = row[RefreshTokenTable.tokenHash],
                isRevoked = row[RefreshTokenTable.isRevoked],
                expiresAt = Date(row[RefreshTokenTable.expiresAt])
            )
        }
    }

    private fun map(row: ResultRow): RefreshToken = mapRefreshToken(row)

    suspend fun byId(id: UUID): RefreshToken? = queryRefreshToken {
        where { RefreshTokenTable.id eq id }
    }.singleOrNull()

    suspend fun byUserId(userId: UUID): List<RefreshToken> = queryRefreshToken {
        where { RefreshTokenTable.userId eq userId }
    }

    suspend fun validByUserId(userId: UUID): RefreshToken? = queryRefreshToken {
        where { RefreshTokenTable.userId eq userId }
        andWhere { RefreshTokenTable.expiresAt greater Instant.now().toEpochMilli() }
        andWhere { RefreshTokenTable.isRevoked eq false }
        orderBy(RefreshTokenTable.expiresAt, SortOrder.DESC)
    }.firstOrNull()

    suspend fun byTokenHash(tokenHash: String): RefreshToken? = queryRefreshToken {
        where { RefreshTokenTable.tokenHash eq tokenHash }
    }.singleOrNull()

    suspend fun validByTokenHash(tokenHash: String): RefreshToken? = queryRefreshToken {
        where { RefreshTokenTable.tokenHash eq tokenHash }
        andWhere { RefreshTokenTable.expiresAt greater Instant.now().toEpochMilli() }
        andWhere { RefreshTokenTable.isRevoked eq false }
        orderBy(RefreshTokenTable.expiresAt, SortOrder.DESC)
    }.firstOrNull()

    suspend fun invalidateToken(userId: UUID, tokenHash: String) = dbQuery {
        val op = Op
            .build { RefreshTokenTable.userId eq userId }
            .and { RefreshTokenTable.tokenHash eq tokenHash }

        RefreshTokenTable.deleteWhere { op }
    }

    suspend fun createToken(userId: UUID, expirationMillis: Duration, tokenHash: String): RefreshToken? = dbQuery {
        val expirationDate = Instant.now().toEpochMilli().date + expirationMillis

        RefreshTokenTable.batchInsert(listOf(Triple(userId, expirationDate, tokenHash))) {
            this[RefreshTokenTable.userId] = it.first
            this[RefreshTokenTable.expiresAt] = it.second.time
            this[RefreshTokenTable.tokenHash] = it.third
        }.map(::map)
    }.singleOrNull()

    private suspend fun queryRefreshToken(query: Query.() -> Query = { this }): List<RefreshToken> {
        return dbQuery {
            RefreshTokenTable
                .selectAll()
                .query()
                .map(::map)
        }
    }
}