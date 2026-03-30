package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.RefreshTokenTable
import dev.dertyp.db.SessionTable
import dev.dertyp.db.UserTable
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class SessionServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: SessionService

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { mockk<ImageService>(relaxed = true) }
            })
        }

        database = TestDatabase.connect(dialect, "session_test")
        transaction(database) {
            SchemaUtils.create(UserTable, SessionTable, RefreshTokenTable)
        }
        service = SessionService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createSession should insert a session`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user"
                it[passwordHash] = "hash"
            }
        }

        val sessionId = service.createSession(userId, "agent", "127.0.0.1")
        assertNotNull(sessionId)
        
        val sessions = service.getSessions(userId)
        assertEquals(1, sessions.size)
        assertEquals("agent", sessions[0].userAgent)
        assertTrue(service.isSessionActive(sessionId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deactivateSession should set isActive to false`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user"
                it[passwordHash] = "hash"
            }
        }

        val sessionId = service.createSession(userId, "agent", "127.0.0.1")
        service.deactivateSession(sessionId, userId)
        
        assertFalse(service.isSessionActive(sessionId))
    }
}
