package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.RefreshTokenTable
import dev.dertyp.db.SessionTable
import dev.dertyp.db.UserTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class RefreshTokenServiceTest {
    private lateinit var database: Database
    private lateinit var service: RefreshTokenService
    private val userId = UUID.randomUUID()

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "refresh_token_test")
        transaction(database) {
            SchemaUtils.create(UserTable, SessionTable, RefreshTokenTable)
            UserTable.insert {
                it[id] = userId
                it[username] = "test"
                it[passwordHash] = "hash"
            }
        }
        service = RefreshTokenService()
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createToken should store token in database`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val tokenHash = "some-hash"
        val sessionId = UUID.randomUUID()
        
        transaction(database) {
            SessionTable.insert {
                it[id] = sessionId
                it[this.userId] = this@RefreshTokenServiceTest.userId
                it[userAgent] = "test"
                it[ipAddress] = "127.0.0.1"
                it[lastActive] = Instant.now().toEpochMilli()
                it[isActive] = true
            }
        }

        val created = service.createToken(userId, 30.days, tokenHash, sessionId)
        assertNotNull(created)
        assertEquals(tokenHash, created?.tokenHash)
        assertEquals(userId, created?.userId)

        val fetched = service.byTokenHash(tokenHash)
        assertEquals(created?.id, fetched?.id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `validByTokenHash should only return valid tokens`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val tokenHash = "valid-hash"
        service.createToken(userId, 1.hours, tokenHash, null)

        val valid = service.validByTokenHash(tokenHash)
        assertNotNull(valid)

        val expiredHash = "expired-hash"
        transaction(database) {
            RefreshTokenTable.insert {
                it[RefreshTokenTable.userId] = this@RefreshTokenServiceTest.userId
                it[RefreshTokenTable.expiresAt] = Instant.now().minusMillis(1000).toEpochMilli()
                it[RefreshTokenTable.tokenHash] = expiredHash
                it[isRevoked] = false
            }
        }
        assertNull(service.validByTokenHash(expiredHash))

        val revokedHash = "revoked-hash"
        val revokedToken = service.createToken(userId, 1.hours, revokedHash, null)
        transaction(database) {
            RefreshTokenTable.update({ RefreshTokenTable.id eq revokedToken?.id }) {
                it[isRevoked] = true
            }
        }
        assertNull(service.validByTokenHash(revokedHash))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `invalidateToken should delete the token`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val hash = "to-be-deleted"
        service.createToken(userId, 1.hours, hash, null)
        assertNotNull(service.byTokenHash(hash))

        service.invalidateToken(userId, hash)
        assertNull(service.byTokenHash(hash))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getSessionId should return correct sessionId`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val sessionId = UUID.randomUUID()
        val hash = "session-hash"
        
        transaction(database) {
            SessionTable.insert {
                it[id] = sessionId
                it[this.userId] = this@RefreshTokenServiceTest.userId
                it[userAgent] = "test"
                it[ipAddress] = "127.0.0.1"
                it[lastActive] = Instant.now().toEpochMilli()
                it[isActive] = true
            }
        }
        
        service.createToken(userId, 1.hours, hash, sessionId)
        assertEquals(sessionId, service.getSessionId(hash))
    }
}
