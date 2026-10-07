package dev.dertyp.migrations

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.ServerConfig
import dev.dertyp.core.db.Dialect
import dev.dertyp.core.db.SchemaTables
import dev.dertyp.db.CustomMigrationTable
import dev.dertyp.db.DatabaseNotAtBaseException
import dev.dertyp.db.DatabaseNotAtBaseException.Reason
import dev.dertyp.db.MigrationBase
import dev.dertyp.db.SearchIndexQueueTable
import dev.dertyp.services.DatabaseManager
import io.ktor.server.config.MapApplicationConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertNotNull

class FlywayMigrationTest {
    private val files = mutableListOf<File>()
    private val managers = mutableListOf<DatabaseManager>()
    private val directories = mutableListOf<File>()

    private val steppingStoneImage = "ghcr.io/dertyp7214/synara:0.0.1-dev"
    private val lastCustomMigration = "FillAlbumReleaseDates"
    private val oldMigrations = (0..109).filter { it != 56 }
    private val laterMigrations = MigrationFiles.versioned(MigrationFiles.resources, Dialect.SQLITE).map { it.version }
    private val headHistory = listOf("1.109" to "SQL_BASELINE") + laterMigrations.map { "1.$it" to "SQL" }
    private val futureMigration = (laterMigrations + MigrationFiles.baseVersion).max() + 1

    private class Target(val driver: String, val url: String, val user: String, val password: String)

    private data class Snapshot(
        val tables: Set<String>,
        val history: List<List<String?>>?,
        val customMigrations: List<List<String?>>?,
        val users: List<List<String?>>?,
    )

    @AfterEach
    fun tearDown() {
        managers.forEach { it.close() }
        directories.forEach { it.deleteRecursively() }
        files.forEach { file ->
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an empty database gets the base with its migrations and a second start changes nothing`(dialect: DbDialect) {
        val target = emptyDatabase(dialect)

        manager(target, admin = true).init()

        val first = snapshot(target)
        val history = assertNotNull(first.history)
        assertEquals(headHistory, history.map { it[1] to it[3] })
        assertEquals(1, first.users?.size)
        assertEquals(emptyList<List<String?>>(), first.customMigrations)

        val missing = manager(target).tempConnection {
            SchemaTables.all.filterNot { it.exists() }.toSet()
        }
        val expectedMissing = if (dialect == DbDialect.SQLITE) setOf(SearchIndexQueueTable) else emptySet()
        assertEquals(expectedMissing, missing)

        if (dialect == DbDialect.POSTGRES) {
            connection(target).use { connection ->
                assertEquals(
                    setOf(
                        "album_artist_change_indexing_trigger",
                        "album_change_indexing_trigger",
                        "album_mb_change_indexing_trigger",
                        "artist_alias_change_indexing_trigger",
                        "artist_change_indexing_trigger",
                        "artist_mb_change_indexing_trigger",
                        "song_artist_change_indexing_trigger",
                        "song_change_indexing_trigger",
                        "song_mb_change_indexing_trigger",
                    ),
                    names(connection, "SELECT tgname FROM pg_trigger WHERE NOT tgisinternal")
                )
                assertEquals(
                    setOf(
                        "queue_for_search_indexing",
                        "trigger_on_album_artist_change",
                        "trigger_on_album_change",
                        "trigger_on_album_mb_change",
                        "trigger_on_artist_alias_change",
                        "trigger_on_artist_change",
                        "trigger_on_artist_mb_change",
                        "trigger_on_song_artist_change",
                        "trigger_on_song_change",
                        "trigger_on_song_mb_change",
                    ),
                    names(
                        connection,
                        "SELECT proname FROM pg_proc WHERE pronamespace = current_schema()::regnamespace"
                    )
                )
                assertEquals(
                    setOf("album", "artist", "song"),
                    names(
                        connection,
                        "SELECT table_name FROM information_schema.columns " +
                            "WHERE column_name = 'search_vector' AND udt_name = 'tsvector'"
                    )
                )
                assertEquals(
                    18,
                    names(
                        connection,
                        "SELECT indexname FROM pg_indexes " +
                            "WHERE schemaname = current_schema() AND indexdef LIKE '%USING gin%'"
                    ).size
                )
            }
        }

        manager(target, admin = true).init()

        assertEquals(first, snapshot(target))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a database upgraded by the last old version applies the later migrations and keeps its history`(
        dialect: DbDialect
    ) {
        val target = upgradedDatabase(dialect)
        val before = snapshot(target)
        val oldHistory = assertNotNull(before.history)
        assertEquals(109, oldHistory.size)
        if (dialect == DbDialect.SQLITE) {
            assertEquals(
                setOf("hue_bridge_userId_bridgeId", "hue_bridge_userId_bridgeId_unique"),
                hueBridgeIndexes(target)
            )
        }

        manager(target).init()

        val after = snapshot(target)
        val history = assertNotNull(after.history)
        assertEquals(oldHistory, history.take(oldHistory.size))
        assertEquals(headHistory.drop(1), history.drop(oldHistory.size).map { it[1] to it[3] })
        assertEquals(before.copy(history = history), after)
        if (dialect == DbDialect.SQLITE) {
            assertEquals(setOf("hue_bridge_userId_bridgeId_unique"), hueBridgeIndexes(target))
        }

        manager(target).init()

        assertEquals(after, snapshot(target))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an upgraded database without the last custom migration is refused`(dialect: DbDialect) {
        val target = upgradedDatabase(dialect, customMigrationRecorded = false)

        assertRefused(target, Reason.CUSTOM_MIGRATIONS_UNFINISHED, foundVersion = "1.109")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an upgraded database without the custom migration table is refused`(dialect: DbDialect) {
        val target = upgradedDatabase(dialect, customMigrationRecorded = false)
        manager(target).tempConnection { SchemaUtils.drop(CustomMigrationTable) }
        assertNull(snapshot(target).customMigrations)

        assertRefused(target, Reason.CUSTOM_MIGRATIONS_UNFINISHED, foundVersion = "1.109")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a database whose history ends below the base is refused`(dialect: DbDialect) {
        val target = upgradedDatabase(dialect, versions = oldMigrations - 109)

        assertRefused(target, Reason.BELOW_BASE, foundVersion = "1.108")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a database with a failed last migration is refused`(dialect: DbDialect) {
        val target = upgradedDatabase(dialect, failed = setOf(109))

        assertRefused(target, Reason.FAILED_MIGRATION, foundVersion = "1.109")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a database with tables but without a history table is refused`(dialect: DbDialect) {
        val target = upgradedDatabase(dialect)
        connection(target).use { connection ->
            connection.createStatement().use { it.execute("DROP TABLE flyway_schema_history") }
        }
        assertNull(snapshot(target).history)

        assertRefused(target, Reason.TABLES_WITHOUT_HISTORY, foundVersion = null)

        assertNull(snapshot(target).history)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a database that holds only an empty history table gets the base`(dialect: DbDialect) {
        val target = emptyDatabase(dialect)
        val noMigrations = Files.createTempDirectory("no_migrations").toFile()
        directories += noMigrations
        Flyway.configure()
            .dataSource(target.url, target.user, target.password)
            .locations("filesystem:${noMigrations.absolutePath}")
            .load()
            .migrate()
        val before = snapshot(target)
        assertEquals(setOf("flyway_schema_history"), before.tables)
        assertEquals(emptyList<List<String?>>(), before.history)

        manager(target, admin = true).init()

        val after = snapshot(target)
        val history = assertNotNull(after.history)
        assertEquals(headHistory, history.map { it[1] to it[3] })
        assertEquals(1, after.users?.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a database with tables and an empty history table is refused`(dialect: DbDialect) {
        val target = upgradedDatabase(dialect)
        connection(target).use { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM flyway_schema_history") }
        }
        assertEquals(emptyList<List<String?>>(), snapshot(target).history)

        assertRefused(target, Reason.TABLES_WITHOUT_HISTORY, foundVersion = null)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a database that is ahead of this build starts`(dialect: DbDialect) {
        val target = upgradedDatabase(dialect)
        manager(target).init()
        insertHistory(
            target,
            firstRank = oldMigrations.size + laterMigrations.size + 1,
            versions = listOf(futureMigration),
            failed = emptySet()
        )
        val before = snapshot(target)
        assertEquals(
            oldMigrations.map { "1.$it" } + headHistory.drop(1).map { it.first } + "1.$futureMigration",
            before.history?.map { it[1] }
        )

        manager(target).init()

        assertEquals(before, snapshot(target))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a database created from the base that is ahead of this build starts`(dialect: DbDialect) {
        val target = emptyDatabase(dialect)
        manager(target).init()
        insertHistory(target, firstRank = headHistory.size + 1, versions = listOf(futureMigration), failed = emptySet())
        val before = snapshot(target)
        assertEquals(headHistory.map { it.first } + "1.$futureMigration", before.history?.map { it[1] })

        manager(target).init()

        assertEquals(before, snapshot(target))
    }

    private fun assertRefused(target: Target, reason: Reason, foundVersion: String?) {
        val before = snapshot(target)
        assertEquals(emptyList<List<String?>>(), before.users)

        val refusal = assertThrows(DatabaseNotAtBaseException::class.java) {
            manager(target, admin = true).init()
        }

        assertEquals(reason, refusal.reason)
        assertEquals(foundVersion, refusal.foundVersion)
        val message = assertNotNull(refusal.message)
        assertTrue(message.contains(steppingStoneImage), message)
        assertTrue(message.contains(reason.description), message)
        assertTrue(message.contains(lastCustomMigration), message)
        assertTrue(message.contains("Found schema version: ${foundVersion ?: "none"}"), message)
        Reason.entries.filter { it != reason }.forEach { other ->
            assertFalse(message.contains(other.description), message)
        }
        assertEquals(before, snapshot(target))
    }

    private fun postgresName() = "flyway_test_${UUID.randomUUID().toString().replace("-", "")}".lowercase()

    private fun postgresTarget(url: String): Target {
        val container = TestDatabase.postgresContainer
        return Target(driver = "org.postgresql.Driver", url = url, user = container.username, password = container.password)
    }

    private fun emptyDatabase(dialect: DbDialect): Target = when (dialect) {
        DbDialect.POSTGRES -> postgresTarget(TestDatabase.getPostgresDbUrl(postgresName()))

        DbDialect.SQLITE -> {
            val file = File.createTempFile("flyway_test", ".db")
            files += file
            Target(driver = "org.sqlite.JDBC", url = "jdbc:sqlite:${file.absolutePath}", user = "", password = "")
        }
    }

    private fun upgradedDatabase(
        dialect: DbDialect,
        versions: List<Int> = oldMigrations,
        failed: Set<Int> = emptySet(),
        customMigrationRecorded: Boolean = true,
    ): Target {
        val target = when (dialect) {
            DbDialect.POSTGRES -> postgresTarget(
                TestDatabase.getMigratedPostgresDbUrl(postgresName(), upTo = MigrationBase.VERSION)
            )

            DbDialect.SQLITE -> emptyDatabase(dialect).also { empty ->
                Flyway.configure()
                    .dataSource(empty.url, empty.user, empty.password)
                    .locations(MigrationBase.location(Dialect.ofDriver(empty.driver)))
                    .target(MigrationBase.VERSION)
                    .placeholderReplacement(false)
                    .load()
                    .migrate()
            }
        }
        connection(target).use { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM flyway_schema_history") }
        }
        insertHistory(target, firstRank = 1, versions = versions, failed = failed)
        if (customMigrationRecorded) {
            val recorded = lastCustomMigration
            manager(target).tempConnection {
                CustomMigrationTable.insert {
                    it[id] = recorded
                    it[executedAt] = 1791326209381
                }
            }
        }
        return target
    }

    private fun insertHistory(target: Target, firstRank: Int, versions: List<Int>, failed: Set<Int>) {
        connection(target).use { connection ->
            connection.prepareStatement(
                "INSERT INTO flyway_schema_history " +
                    "(installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) " +
                    "VALUES (?, ?, ?, 'JDBC', ?, NULL, ?, ?, ?)"
            ).use { statement ->
                versions.forEachIndexed { index, minor ->
                    statement.setInt(1, firstRank + index)
                    statement.setString(2, "1.$minor")
                    statement.setString(3, "Step$minor")
                    statement.setString(4, "dev.dertyp.db.migrations.V1_${minor}__Step$minor")
                    statement.setString(5, target.user)
                    statement.setInt(6, 5)
                    statement.setBoolean(7, minor !in failed)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun manager(target: Target, admin: Boolean = false): DatabaseManager {
        val settings = buildList {
            add("storage.driverClassName" to target.driver)
            add("storage.jdbcURL" to target.url)
            add("storage.user" to target.user)
            add("storage.password" to target.password)
            if (admin) {
                add("client.id" to "test-client")
                add("client.secret" to "test-secret")
            }
        }
        return DatabaseManager(ServerConfig(MapApplicationConfig(*settings.toTypedArray()))).also { managers += it }
    }

    private fun hueBridgeIndexes(target: Target): Set<String> = connection(target).use { connection ->
        names(connection, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'hue_bridge' AND sql IS NOT NULL")
    }

    private fun connection(target: Target): Connection =
        DriverManager.getConnection(target.url, target.user, target.password)

    private fun snapshot(target: Target): Snapshot = connection(target).use { connection ->
        val tables = connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rows ->
            buildSet { while (rows.next()) add(rows.getString("TABLE_NAME")) }
        }
        Snapshot(
            tables = tables,
            history = rows(connection, tables, "flyway_schema_history", "installed_rank"),
            customMigrations = rows(connection, tables, "customMigration", "id"),
            users = rows(connection, tables, "user", "id"),
        )
    }

    private fun rows(connection: Connection, tables: Set<String>, table: String, order: String): List<List<String?>>? {
        if (tables.none { it.equals(table, ignoreCase = true) }) return null
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM \"${tables.first { it.equals(table, ignoreCase = true) }}\" ORDER BY $order")
                .use { result ->
                    buildList {
                        while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
                    }
                }
        }
    }

    private fun names(connection: Connection, query: String): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }
}
