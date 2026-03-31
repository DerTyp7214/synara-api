package dev.dertyp.services

import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
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

        val manager = DatabaseManager(environment)
        
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
}
