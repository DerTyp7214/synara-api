package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.sha256
import dev.dertyp.data.User
import dev.dertyp.db.ApiKeyTable
import dev.dertyp.db.UserTable
import dev.dertyp.dbQuery
import dev.dertyp.plugins.ApiKeyScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiKeyServiceTest : KoinTest {

    private val userService = mockk<UserService>()
    private val userId = UUID.randomUUID()

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "api_key_test")
        dbQuery {
            SchemaUtils.create(UserTable, ApiKeyTable)
            UserTable.insert {
                it[id] = userId
                it[username] = "tester"
                it[passwordHash] = "x"
            }
        }
        coEvery { userService.findUserById(userId) } returns mockk<User> { every { id } returns userId }

        startKoin { modules(module { single { userService }; single { ApiKeyScopeRegistry() } }) }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `created key resolves to its user and stores only a hash`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = ApiKeyService()

        val raw = service.createKey(userId, "mpv", listOf(ApiKeyScope.Radio.id))
        assertTrue(raw.startsWith("synara_"), "key should carry the identifying prefix")

        val resolved = service.resolveUser(raw, ApiKeyScope.Radio)
        assertEquals(userId, resolved?.id, "valid key resolves to the owning user")

        val storedHash = dbQuery { ApiKeyTable.selectAll().single()[ApiKeyTable.keyHash] }
        assertNotEquals(raw, storedHash)
        assertEquals(raw.toByteArray().sha256(), storedHash)

        assertNull(service.resolveUser("synara_wrongkey", ApiKeyScope.Radio), "unknown key resolves to nothing")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `revoked key stops resolving`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = ApiKeyService()

        val raw = service.createKey(userId, "mpv", listOf(ApiKeyScope.Radio.id))
        val keyId = dbQuery { ApiKeyTable.selectAll().single()[ApiKeyTable.id].value }

        assertTrue(service.revokeKey(keyId, userId))
        assertNull(service.resolveUser(raw, ApiKeyScope.Radio), "revoked key must not authenticate")

        val keys = service.listKeys(userId)
        assertEquals(1, keys.size)
        assertTrue(keys.single().isRevoked)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `expired key does not resolve`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = ApiKeyService()

        val raw = "synara_expired"
        dbQuery {
            ApiKeyTable.insert {
                it[keyHash] = raw.toByteArray().sha256()
                it[ApiKeyTable.userId] = this@ApiKeyServiceTest.userId
                it[label] = "old"
                it[expiresAt] = System.currentTimeMillis() - 1000
                it[scopes] = ApiKeyScope.Radio.id
            }
        }
        assertNull(service.resolveUser(raw, ApiKeyScope.Radio), "expired key must not authenticate")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `keys only resolve for scopes they hold`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = ApiKeyService()

        val raw = service.createKey(userId, "radio remote", listOf(ApiKeyScope.Radio.id))
        assertEquals(userId, service.resolveUser(raw, ApiKeyScope.Radio)?.id)
        assertNull(
            service.resolveUser(raw, ApiKeyScope.Plugin("subsonic", "subsonic", "n", "d")),
            "key must not satisfy scopes it was not granted",
        )
        assertEquals(listOf(ApiKeyScope.Radio.id), service.listKeys(userId).single().scopes)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stored key string is retrievable by its owner`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = ApiKeyService()

        val raw = service.createKey(userId, "mpv", listOf(ApiKeyScope.Radio.id))
        val keyId = dbQuery { ApiKeyTable.selectAll().single()[ApiKeyTable.id].value }

        assertEquals(raw, service.getKeyString(keyId, userId))
        assertNull(service.getKeyString(keyId, UUID.randomUUID()), "other users must not read the secret")

        val legacyId = UUID.randomUUID()
        dbQuery {
            ApiKeyTable.insert {
                it[id] = legacyId
                it[keyHash] = "legacy".toByteArray().sha256()
                it[ApiKeyTable.userId] = this@ApiKeyServiceTest.userId
                it[label] = "legacy"
                it[scopes] = ApiKeyScope.Radio.id
            }
        }
        assertNull(service.getKeyString(legacyId, userId), "legacy keys have no stored secret")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unregistered scopes are rejected at creation`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = ApiKeyService()
        val error = runCatching { service.createKey(userId, "bad", listOf("does-not-exist")) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "unknown scope must be rejected")
    }
}
