package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.db.dbQuery
import dev.dertyp.core.sha256
import dev.dertyp.db.ApiKeyTable
import dev.dertyp.db.UserTable
import dev.dertyp.services.credentials.CredentialCipher
import io.ktor.server.config.MapApplicationConfig
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptApiKeysTest : KoinTest {
    private val cipher = CredentialCipher(MapApplicationConfig("credentials.encryptionKey" to "test-key"))
    private val ownerId = UUID.randomUUID()

    private fun setup(dialect: DbDialect) = runBlocking {
        startKoin { modules(module { single { cipher } }) }
        TestDatabase.connect(dialect, "encrypt_api_keys_test")
        dbQuery {
            SchemaUtils.create(UserTable, ApiKeyTable)
            UserTable.insert {
                it[id] = ownerId
                it[username] = "owner"
                it[passwordHash] = "x"
            }
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private suspend fun insertKey(raw: String, stored: String?): UUID = dbQuery {
        ApiKeyTable.insert {
            it[keyHash] = raw.toByteArray().sha256()
            it[rawKey] = stored
            it[userId] = ownerId
            it[label] = raw
            it[scopes] = "radio"
        }[ApiKeyTable.id].value
    }

    private suspend fun storedKeys(): Map<UUID, String?> = dbQuery {
        ApiKeyTable.selectAll().associate { it[ApiKeyTable.id].value to it[ApiKeyTable.rawKey] }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `plain keys are encrypted and decrypt back`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val first = insertKey("synara_first", "synara_first")
        val second = insertKey("synara_second", "synara_second")

        EncryptApiKeys().migrate()

        val stored = storedKeys()
        mapOf(first to "synara_first", second to "synara_second").forEach { (id, raw) ->
            val value = stored.getValue(id)!!
            assertTrue(cipher.isEncrypted(value))
            assertFalse(value.contains(raw))
            assertEquals(raw, cipher.decrypt(raw.toByteArray().sha256(), value))
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `encrypted and missing keys stay untouched`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val sealed = cipher.encrypt("synara_sealed".toByteArray().sha256(), "synara_sealed")
        val encrypted = insertKey("synara_sealed", sealed)
        val legacy = insertKey("synara_legacy", null)

        EncryptApiKeys().migrate()

        val stored = storedKeys()
        assertEquals(sealed, stored.getValue(encrypted))
        assertNull(stored.getValue(legacy))
    }
}
