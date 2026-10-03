package dev.dertyp.services

import dev.dertyp.TestDatabase
import dev.dertyp.config.ServerConfig
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseManagerTest {

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `init should run migrations and create initial user`() {
        val dbFile = File.createTempFile("dbmgr_test", ".db")
        dbFile.deleteOnExit()

        val config = MapApplicationConfig(
            "storage.driverClassName" to "org.sqlite.JDBC",
            "storage.jdbcURL" to "jdbc:sqlite:${dbFile.absolutePath}",
            "storage.user" to "",
            "storage.password" to "",
            "client.id" to "test-client",
            "client.secret" to "test-secret"
        )
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns config

        val manager = DatabaseManager(ServerConfig(environment.config))

        startKoin {
            modules(module {
                single { manager }
            })
        }

        try {
            manager.init()
            assertTrue(dbFile.length() > 0)
        } finally {
            manager.close()
            dbFile.delete()
        }
    }

    @Test
    fun `postgres connections run without jit`() {
        val container = assertNotNull(TestDatabase.postgresContainer)
        val manager = DatabaseManager(
            ServerConfig(
                MapApplicationConfig(
                    "storage.driverClassName" to "org.postgresql.Driver",
                    "storage.jdbcURL" to container.jdbcUrl,
                    "storage.user" to container.username,
                    "storage.password" to container.password,
                )
            )
        )

        try {
            val jit = manager.tempConnection {
                exec("SHOW jit", explicitStatementType = StatementType.SELECT) { resultSet ->
                    resultSet.next()
                    resultSet.getString(1)
                }
            }
            assertEquals("off", jit)
        } finally {
            manager.close()
        }
    }
}
