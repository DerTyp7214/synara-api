package dev.dertyp.migrations

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.ServerConfig
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.CollectionAlbumTable
import dev.dertyp.db.CollectionArtistTable
import dev.dertyp.db.CollectionPlaylistTable
import dev.dertyp.db.CollectionSongTable
import dev.dertyp.db.EntityChangeScopeTable
import dev.dertyp.db.EntityChangeTable
import dev.dertyp.db.PlaylistSongTable
import dev.dertyp.db.UserEntityChangeTable
import dev.dertyp.db.UserPlaylistSongTable
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

    @Test
    fun `a postgres database that stopped before the entity change migration upgrades to the current schema`() {
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
        val tables = arrayOf("entity_change_scope", "user_entity_change", "entity_change", "entity_change_tracking")
        val memberColumns = listOf(
            UserPlaylistSongTable.songId,
            PlaylistSongTable.songId,
            CollectionSongTable.songId,
            CollectionAlbumTable.albumId,
            CollectionArtistTable.artistId,
            CollectionPlaylistTable.playlistId,
        )
        val (memberIndices, changeIndices) = databaseManager.tempConnection {
            memberColumns.map { column ->
                column.table.indices.single { index -> index.columns == listOf(column) }.indexName.lowercase()
            } to listOf(EntityChangeTable, UserEntityChangeTable, EntityChangeScopeTable)
                .flatMap { table -> table.indices.map { it.indexName.lowercase() } }
        }

        databaseManager.init()
        databaseManager.close()

        DriverManager.getConnection(dbUrl, container.username, container.password).use { connection ->
            assertTrue(appliedVersions(connection).contains("1.108"))
            assertEquals(tables.toSet(), tableNames(connection, *tables))
            assertEquals(6, memberIndices.toSet().size)
            assertEquals(7, changeIndices.toSet().size)
            assertEquals((memberIndices + changeIndices).toSet(), indexNames(connection, memberIndices + changeIndices))
            connection.createStatement().use { statement ->
                for (table in tables) statement.execute("DROP TABLE $table")
                for (index in memberIndices) statement.execute("DROP INDEX $index")
            }
            val reverted = appliedVersions(connection).filter { it.substringAfter('.').toInt() >= 108 }
            connection.prepareStatement("DELETE FROM flyway_schema_history WHERE version = ?").use { statement ->
                for (version in reverted) {
                    statement.setString(1, version)
                    statement.executeUpdate()
                }
            }
            assertEquals(emptySet<String>(), tableNames(connection, *tables))
            assertEquals(emptySet<String>(), indexNames(connection, memberIndices + changeIndices))
            assertFalse(appliedVersions(connection).contains("1.108"))
        }

        assertDoesNotThrow {
            databaseManager.init()
        }
        databaseManager.close()

        DriverManager.getConnection(dbUrl, container.username, container.password).use { connection ->
            assertEquals(tables.toSet(), tableNames(connection, *tables))
            assertEquals((memberIndices + changeIndices).toSet(), indexNames(connection, memberIndices + changeIndices))
            assertTrue(appliedVersions(connection).contains("1.108"))
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a database that stopped before the album release date marker migration upgrades to the current schema`(
        dialect: DbDialect
    ) {
        if (dialect == DbDialect.POSTGRES && TestDatabase.postgresContainer == null) {
            println("Skipping PostgreSQL flyway upgrade test because Docker is not available.")
            return
        }

        val dbDriver: String
        val dbUrl: String
        val user: String
        val pass: String
        when (dialect) {
            DbDialect.POSTGRES -> {
                dbDriver = "org.postgresql.Driver"
                dbUrl = TestDatabase.getPostgresDbUrl(
                    "flyway_upgrade_test_${UUID.randomUUID().toString().replace("-", "")}".lowercase()
                )
                user = TestDatabase.postgresContainer!!.username
                pass = TestDatabase.postgresContainer!!.password
            }

            DbDialect.SQLITE -> {
                currentFile = File.createTempFile("flyway_upgrade_test", ".db")
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
        val databaseManager = DatabaseManager(ServerConfig(config))
        startKoin {
            modules(module {
                single { databaseManager }
            })
        }

        databaseManager.init()
        databaseManager.close()

        val unrelated = AlbumTable.lastProviderEnrichment
        val (dropUnrelated, restoreUnrelated) = databaseManager.tempConnection {
            unrelated.dropStatement() to unrelated.createStatement()
        }

        DriverManager.getConnection(dbUrl, user, pass).use { connection ->
            assertTrue(appliedVersions(connection).contains("1.109"))
            assertTrue(hasReleaseDateMarker(connection))
            assertTrue(hasAlbumColumn(connection, unrelated.name))
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE album DROP COLUMN \"releaseDateEstimated\"")
                for (sql in dropUnrelated) statement.execute(sql)
            }
            assertFalse(hasAlbumColumn(connection, unrelated.name))
            val reverted = appliedVersions(connection).filter { it.substringAfter('.').toInt() >= 109 }
            connection.prepareStatement("DELETE FROM flyway_schema_history WHERE version = ?").use { statement ->
                for (version in reverted) {
                    statement.setString(1, version)
                    statement.executeUpdate()
                }
            }
            assertFalse(hasReleaseDateMarker(connection))
            assertFalse(appliedVersions(connection).contains("1.109"))
        }

        assertDoesNotThrow {
            databaseManager.init()
        }
        databaseManager.close()

        DriverManager.getConnection(dbUrl, user, pass).use { connection ->
            assertTrue(hasReleaseDateMarker(connection))
            assertTrue(appliedVersions(connection).contains("1.109"))
            assertFalse(hasAlbumColumn(connection, unrelated.name))
            connection.createStatement().use { statement ->
                for (sql in restoreUnrelated) statement.execute(sql)
            }
            assertTrue(hasAlbumColumn(connection, unrelated.name))
            connection.prepareStatement("DELETE FROM flyway_schema_history WHERE version = ?").use { statement ->
                statement.setString(1, "1.109")
                statement.executeUpdate()
            }
            assertFalse(appliedVersions(connection).contains("1.109"))
        }

        assertDoesNotThrow {
            databaseManager.init()
        }
        databaseManager.close()

        DriverManager.getConnection(dbUrl, user, pass).use { connection ->
            assertTrue(appliedVersions(connection).contains("1.109"))
            assertTrue(hasReleaseDateMarker(connection))
            assertTrue(hasAlbumColumn(connection, unrelated.name))
        }
    }

    private fun hasReleaseDateMarker(connection: Connection): Boolean =
        hasAlbumColumn(connection, "releaseDateEstimated")

    private fun hasAlbumColumn(connection: Connection, name: String): Boolean =
        connection.metaData.getColumns(null, null, "album", name).use { it.next() }

    private fun indexNames(connection: Connection, names: List<String>): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }.intersect(names.toSet())

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
