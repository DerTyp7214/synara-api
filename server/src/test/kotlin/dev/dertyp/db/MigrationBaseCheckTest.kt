package dev.dertyp.db

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.db.Dialect
import dev.dertyp.db.DatabaseNotAtBaseException.Reason
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertNotNull

class MigrationBaseCheckTest {
    private val files = mutableListOf<File>()

    private class Target(val dialect: Dialect, val driver: String, val url: String, val user: String, val password: String)

    @AfterEach
    fun tearDown() {
        files.forEach { file ->
            if (file.isDirectory) file.deleteRecursively()
            listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
        }
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an applied migration that this build lacks below a newer one is refused`(dialect: DbDialect) {
        val target = baseDatabase(dialect)
        recordApplied(target, 111)
        val before = history(target)

        val refusal = check(target, later(112)).refusal()

        assertEquals(Reason.MISSING_MIGRATION, assertNotNull(refusal).reason)
        assertEquals("1.111", refusal.foundVersion)
        assertEquals(before, history(target))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an applied migration above everything this build has is accepted`(dialect: DbDialect) {
        val target = baseDatabase(dialect)
        recordApplied(target, 111)
        val before = history(target)

        assertNull(check(target).refusal())
        assertEquals(before, history(target))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an applied migration that this build has is accepted with a newer one pending`(dialect: DbDialect) {
        val target = baseDatabase(dialect)
        recordApplied(target, 111)
        val before = history(target)

        assertNull(check(target, later(111, 112)).refusal())
        assertEquals(before, history(target))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a database at the base is accepted with a newer migration pending`(dialect: DbDialect) {
        val target = baseDatabase(dialect, upTo = MigrationBase.VERSION)

        assertNull(check(target, later(111)).refusal())
        assertEquals(listOf(MigrationBase.VERSION), history(target))
    }

    private fun later(vararg versions: Int): File {
        val directory = Files.createTempDirectory("later_migrations").toFile()
        files += directory
        versions.forEach { File(directory, "V1_${it}__Later$it.sql").writeText("SELECT 1;\n") }
        return directory
    }

    private fun check(target: Target, additional: File? = null): MigrationBaseCheck =
        MigrationBaseCheck(flyway(target, additional), Database.connect(target.url, target.driver, target.user, target.password))

    private fun flyway(target: Target, additional: File?, upTo: String? = null): Flyway {
        val locations = listOfNotNull(MigrationBase.location(target.dialect), additional?.let { "filesystem:${it.absolutePath}" })
        val configuration = Flyway.configure()
            .dataSource(target.url, target.user, target.password)
            .locations(*locations.toTypedArray())
            .ignoreMigrationPatterns("versioned:missing", "*:future")
            .placeholderReplacement(false)
            .baselineOnMigrate(false)
        if (upTo != null) configuration.target(upTo)
        return configuration.load()
    }

    private fun baseDatabase(dialect: DbDialect, upTo: String? = null): Target = when (dialect) {
        DbDialect.POSTGRES -> {
            val container = TestDatabase.postgresContainer
            Target(
                dialect = Dialect.POSTGRES,
                driver = "org.postgresql.Driver",
                url = TestDatabase.getMigratedPostgresDbUrl(
                    "base_check_${UUID.randomUUID().toString().replace("-", "")}".lowercase(),
                    upTo,
                ),
                user = container.username,
                password = container.password,
            )
        }

        DbDialect.SQLITE -> {
            val file = File.createTempFile("base_check", ".db")
            files += file
            Target(Dialect.SQLITE, "org.sqlite.JDBC", "jdbc:sqlite:${file.absolutePath}", "", "").also {
                flyway(it, additional = null, upTo = upTo).migrate()
            }
        }
    }

    private fun recordApplied(target: Target, minor: Int) {
        DriverManager.getConnection(target.url, target.user, target.password).use { connection ->
            connection.prepareStatement(
                "INSERT INTO flyway_schema_history " +
                    "(installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) " +
                    "VALUES (?, ?, ?, 'SQL', ?, NULL, ?, 5, ?)"
            ).use { statement ->
                statement.setInt(1, minor)
                statement.setString(2, "1.$minor")
                statement.setString(3, "Later$minor")
                statement.setString(4, "V1_${minor}__Later$minor.sql")
                statement.setString(5, target.user)
                statement.setBoolean(6, true)
                statement.executeUpdate()
            }
        }
    }

    private fun history(target: Target): List<String> =
        DriverManager.getConnection(target.url, target.user, target.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version FROM flyway_schema_history ORDER BY installed_rank").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }
}
