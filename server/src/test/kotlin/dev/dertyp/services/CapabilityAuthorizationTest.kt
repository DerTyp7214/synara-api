package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.db.UserCapabilityTable
import dev.dertyp.db.UserTable
import dev.dertyp.utils.withAuthorization
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class CapabilityAuthorizationTest : KoinTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "cap_auth_test")
        transaction(database) {
            SchemaUtils.create(UserTable, UserCapabilityTable)
        }
        
        startKoin {
            modules(module {
                single { mockk<SongService>(relaxed = true) }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should throw UnauthorizedException when capability is missing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userWithoutEdit = User(
            id = UUID.randomUUID(),
            username = "noedit",
            passwordHash = "",
            isAdmin = false,
            capabilities = emptyList()
        )

        val songService = mockk<SongService>(relaxed = true)
        val rpcService = SongRpcService(userWithoutEdit, songService)
        val authorizedService = rpcService.withAuthorization<ISongService>(userWithoutEdit)

        assertThrows(UnauthorizedException::class.java) {
            runBlocking {
                authorizedService.setLyrics(UUID.randomUUID(), listOf("lyrics"))
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should allow access when capability is present`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userWithEdit = User(
            id = UUID.randomUUID(),
            username = "withedit",
            passwordHash = "",
            isAdmin = false,
            capabilities = listOf(UserCapability.EDIT)
        )

        val songService = mockk<SongService>(relaxed = true)
        val rpcService = SongRpcService(userWithEdit, songService)
        val authorizedService = rpcService.withAuthorization<ISongService>(userWithEdit)

        authorizedService.setLyrics(UUID.randomUUID(), listOf("lyrics"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should allow access when user is admin regardless of capabilities`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val adminUser = User(
            id = UUID.randomUUID(),
            username = "admin",
            passwordHash = "",
            isAdmin = true,
            capabilities = emptyList()
        )

        val songService = mockk<SongService>(relaxed = true)
        val rpcService = SongRpcService(adminUser, songService)
        val authorizedService = rpcService.withAuthorization<ISongService>(adminUser)

        authorizedService.setLyrics(UUID.randomUUID(), listOf("lyrics"))
    }
}
