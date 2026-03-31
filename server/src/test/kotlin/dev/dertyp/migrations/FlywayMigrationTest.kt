package dev.dertyp.migrations

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.services.DatabaseManager
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.File
import java.util.UUID

class FlywayMigrationTest : KoinTest {
    private var currentFile: File? = null

    @AfterEach
    fun tearDown() {
        stopKoin()
        currentFile?.delete()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `test all flyway migrations run successfully`(dialect: DbDialect) {
        if (dialect == DbDialect.POSTGRES && TestDatabase.postgresContainer == null) {
            println("Skipping PostgreSQL flyway migration test because Docker is not available.")
            return
        }

        val environment = mockk<ApplicationEnvironment>()
        
        val dbDriver: String
        val dbUrl: String
        val user: String
        val pass: String
        
        when (dialect) {
            DbDialect.POSTGRES -> {
                dbDriver = if (TestDatabase.postgresContainer != null) "org.postgresql.Driver" else "org.h2.Driver"
                dbUrl = TestDatabase.getPostgresDbUrl("flyway_test_${UUID.randomUUID().toString().replace("-", "")}".lowercase())
                user = TestDatabase.postgresContainer?.username ?: "sa"
                pass = TestDatabase.postgresContainer?.password ?: ""
            }
            DbDialect.SQLITE -> {
                currentFile = File.createTempFile("flyway_test", ".db")
                dbDriver = "org.sqlite.JDBC"
                dbUrl = "jdbc:sqlite:${currentFile!!.absolutePath}"
                user = "sa"
                pass = ""
            }
        }
        
        val config = MapApplicationConfig(
            "storage.driverClassName" to dbDriver,
            "storage.jdbcURL" to dbUrl,
            "storage.user" to user,
            "storage.password" to pass
        )
        
        every { environment.config } returns config
        
        val databaseManager = DatabaseManager(environment)

        startKoin {
            modules(module {
                single { databaseManager }
            })
        }

        assertDoesNotThrow {
            databaseManager.init()
        }
        databaseManager.close()
    }
}
