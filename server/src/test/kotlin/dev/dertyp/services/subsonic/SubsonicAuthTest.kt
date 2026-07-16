package dev.dertyp.services.subsonic

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.User
import dev.dertyp.db.ApiKeyTable
import dev.dertyp.db.SubsonicCredentialTable
import dev.dertyp.db.UserTable
import dev.dertyp.dbQuery
import dev.dertyp.plugins.ApiKeyScope
import dev.dertyp.services.ApiKeyScopeRegistry
import dev.dertyp.services.ApiKeyService
import dev.dertyp.services.UserService
import io.ktor.http.parametersOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.koin.dsl.module

class SubsonicAuthTest : KoinTest {
    private val userId = UUID.randomUUID()
    private val testUser = User(id = userId, username = "tester", passwordHash = "irrelevant")
    private val userService = mockk<UserService>()

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun setup(dialect: DbDialect): SubsonicAuthenticator = runBlocking {
        TestDatabase.connect(dialect, "subsonic_auth_test")
        dbQuery {
            SchemaUtils.create(UserTable, ApiKeyTable, SubsonicCredentialTable)
            UserTable.insert {
                it[id] = userId
                it[username] = "tester"
                it[passwordHash] = "x"
            }
        }
        coEvery { userService.findUserByUsername("tester") } returns testUser
        coEvery { userService.findUserByUsername("nobody") } returns null
        coEvery { userService.findUserById(userId) } returns testUser

        val registry = ApiKeyScopeRegistry()
        registry.register(SubsonicPlugin.SCOPE, "subsonic")
        startKoin { modules(module { single { userService }; single { registry } }) }

        val credentialService = SubsonicCredentialService()
        credentialService.regenerate(userId, "tester")
        SubsonicAuthenticator(ApiKeyService(), credentialService)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private suspend fun secret(): String {
        val credentialService = SubsonicCredentialService()
        return credentialService.secretForUsername("tester")!!.second
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `token auth succeeds with correct md5 and fails otherwise`(dialect: DbDialect) = runBlocking {
        val auth = setup(dialect)
        val salt = "abc123"

        val ok = auth.authenticate(parametersOf("u" to listOf("tester"), "t" to listOf(md5Hex(secret() + salt)), "s" to listOf(salt)))
        assertEquals(userId, assertIs<SubsonicAuthResult.Ok>(ok).user.id)

        val wrong = auth.authenticate(parametersOf("u" to listOf("tester"), "t" to listOf(md5Hex("wrong$salt")), "s" to listOf(salt)))
        assertEquals(40, assertIs<SubsonicAuthResult.Failure>(wrong).code)

        val unknown = auth.authenticate(parametersOf("u" to listOf("nobody"), "t" to listOf(md5Hex(secret() + salt)), "s" to listOf(salt)))
        assertEquals(40, assertIs<SubsonicAuthResult.Failure>(unknown).code)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `password auth accepts plain and hex encoded values`(dialect: DbDialect) = runBlocking {
        val auth = setup(dialect)
        val plain = auth.authenticate(parametersOf("u" to listOf("tester"), "p" to listOf(secret())))
        assertEquals(userId, assertIs<SubsonicAuthResult.Ok>(plain).user.id)

        val hex = secret().toByteArray().joinToString("") { "%02x".format(it) }
        val encoded = auth.authenticate(parametersOf("u" to listOf("tester"), "p" to listOf("enc:$hex")))
        assertEquals(userId, assertIs<SubsonicAuthResult.Ok>(encoded).user.id)

        val wrong = auth.authenticate(parametersOf("u" to listOf("tester"), "p" to listOf("nope")))
        assertEquals(40, assertIs<SubsonicAuthResult.Failure>(wrong).code)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `api key auth requires the subsonic scope`(dialect: DbDialect) = runBlocking {
        val auth = setup(dialect)
        val apiKeyService = ApiKeyService()

        val scoped = apiKeyService.createKey(userId, "subsonic client", listOf(SubsonicPlugin.SCOPE.id))
        val ok = auth.authenticate(parametersOf("apiKey" to listOf(scoped)))
        assertEquals(userId, assertIs<SubsonicAuthResult.Ok>(ok).user.id)

        val unscoped = apiKeyService.createKey(userId, "radio only", emptyList())
        val denied = auth.authenticate(parametersOf("apiKey" to listOf(unscoped)))
        assertEquals(44, assertIs<SubsonicAuthResult.Failure>(denied).code)

        val conflicting = auth.authenticate(parametersOf("apiKey" to listOf(scoped), "u" to listOf("tester")))
        assertEquals(43, assertIs<SubsonicAuthResult.Failure>(conflicting).code)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `missing credentials yield error 42`(dialect: DbDialect) = runBlocking {
        val auth = setup(dialect)
        val none = auth.authenticate(parametersOf())
        assertEquals(42, assertIs<SubsonicAuthResult.Failure>(none).code)

        val userOnly = auth.authenticate(parametersOf("u" to listOf("tester")))
        assertEquals(42, assertIs<SubsonicAuthResult.Failure>(userOnly).code)
    }
}
