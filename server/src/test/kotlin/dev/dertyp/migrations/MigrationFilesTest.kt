package dev.dertyp.migrations

import dev.dertyp.core.db.Dialect
import io.github.classgraph.ClassGraph
import org.flywaydb.core.api.migration.JavaMigration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationFilesTest {
    private val dialects = listOf(Dialect.POSTGRES, Dialect.SQLITE)

    @Test
    fun `no class is a schema migration`() {
        val classes = ClassGraph()
            .enableClassInfo()
            .acceptPackages("dev.dertyp")
            .scan().use { scan ->
                scan.getClassesImplementing(JavaMigration::class.java.name).map { it.name }.sorted()
            }

        assertEquals(
            emptyList<String>(),
            classes,
            "Schema migrations are SQL files written by :server:generateMigration, one per database type."
        )
    }

    @Test
    fun `both database types hold the same migrations`() {
        val migrations = dialects.associateWith { dialect ->
            MigrationFiles.versioned(MigrationFiles.resources, dialect).map { "${it.version} ${it.name}" }
        }

        assertEquals(migrations.getValue(Dialect.POSTGRES), migrations.getValue(Dialect.SQLITE))
    }

    @Test
    fun `migration versions are unique and above the base`() {
        dialects.forEach { dialect ->
            val versions = MigrationFiles.versioned(MigrationFiles.resources, dialect).map { it.version }

            assertEquals(versions.distinct(), versions, dialect.name)
            assertEquals(emptyList<Int>(), versions.filter { it <= MigrationFiles.baseVersion }, dialect.name)
        }
    }

    @Test
    fun `the migration folders hold only the base and versioned migrations`() {
        dialects.forEach { dialect ->
            assertTrue(MigrationFiles.folder(MigrationFiles.resources, dialect).isDirectory, dialect.name)
            assertEquals(emptyList<String>(), MigrationFiles.unexpected(MigrationFiles.resources, dialect), dialect.name)
        }
    }
}
