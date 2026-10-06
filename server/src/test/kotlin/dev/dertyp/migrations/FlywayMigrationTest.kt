package dev.dertyp.migrations

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.ServerConfig
import dev.dertyp.services.DatabaseManager
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
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
                dbUrl = TestDatabase.getPostgresDbUrl(
                    "flyway_test_${
                        UUID.randomUUID().toString().replace("-", "")
                    }".lowercase()
                )
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

        val databaseManager = DatabaseManager(ServerConfig(environment.config))

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

    @Test
    fun `a postgres database that stopped before the album title tags migration upgrades to the current schema`() {
        val container = TestDatabase.postgresContainer
        if (container == null) {
            println("Skipping PostgreSQL flyway upgrade test because Docker is not available.")
            return
        }

        val dbUrl = TestDatabase.getPostgresDbUrl(
            "flyway_upgrade_test_${UUID.randomUUID().toString().replace("-", "")}".lowercase()
        )
        val config = MapApplicationConfig(
            "storage.driverClassName" to "org.postgresql.Driver",
            "storage.jdbcURL" to dbUrl,
            "storage.user" to container.username,
            "storage.password" to container.password
        )
        val databaseManager = DatabaseManager(ServerConfig(config))
        startKoin {
            modules(module {
                single { databaseManager }
            })
        }

        databaseManager.init()
        databaseManager.close()

        DriverManager.getConnection(dbUrl, container.username, container.password).use { connection ->
            assertTrue(appliedVersions(connection).containsAll(listOf("1.106", "1.107")))
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE album DROP COLUMN \"versionGroupId\"")
                statement.execute("ALTER TABLE album DROP COLUMN title_tags")
                statement.execute("DROP TABLE album_title_tag")
                statement.execute("DROP TABLE album_version_group")
            }
            val reverted = appliedVersions(connection).filter { it.substringAfter('.').toInt() >= 106 }
            connection.prepareStatement("DELETE FROM flyway_schema_history WHERE version = ?").use { statement ->
                for (version in reverted) {
                    statement.setString(1, version)
                    statement.executeUpdate()
                }
            }
            assertEquals(emptySet<String>(), tableNames(connection, "album_title_tag", "album_version_group"))
            assertEquals(emptySet<String>(), albumColumns(connection, "title_tags", "versionGroupId"))
            assertFalse(appliedVersions(connection).any { it == "1.106" || it == "1.107" })
        }

        assertDoesNotThrow {
            databaseManager.init()
        }
        databaseManager.close()

        DriverManager.getConnection(dbUrl, container.username, container.password).use { connection ->
            assertEquals(
                setOf("album_title_tag", "album_version_group"),
                tableNames(connection, "album_title_tag", "album_version_group")
            )
            assertEquals(
                setOf("title_tags", "versionGroupId"),
                albumColumns(connection, "title_tags", "versionGroupId")
            )
            assertTrue(appliedVersions(connection).containsAll(listOf("1.106", "1.107")))
        }
    }

    private fun appliedVersions(connection: Connection): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL AND success"
            ).use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }

    private fun tableNames(connection: Connection, vararg names: String): Set<String> =
        connection.metaData.getTables(null, "public", "%", arrayOf("TABLE")).use { rows ->
            buildSet { while (rows.next()) add(rows.getString("TABLE_NAME")) }
        }.intersect(names.toSet())

    private fun albumColumns(connection: Connection, vararg names: String): Set<String> =
        connection.metaData.getColumns(null, "public", "album", "%").use { rows ->
            buildSet { while (rows.next()) add(rows.getString("COLUMN_NAME")) }
        }.intersect(names.toSet())
}
