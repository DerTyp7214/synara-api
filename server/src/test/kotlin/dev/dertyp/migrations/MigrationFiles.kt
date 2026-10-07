package dev.dertyp.migrations

import dev.dertyp.core.db.Dialect
import dev.dertyp.db.MigrationBase
import java.io.File

class MigrationFile(val file: File, val version: Int, val name: String)

object MigrationFiles {
    private val versioned = Regex("V1_(\\d+)__(\\w+)\\.sql")
    private val baseFile = "B${MigrationBase.VERSION.replace('.', '_')}__Base.sql"

    val baseVersion: Int = MigrationBase.VERSION.substringAfter('.').toInt()

    val projectRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    val resources: File = File(projectRoot, "server/src/main/resources/db/migrations")

    fun folder(root: File, dialect: Dialect): File = File(root, MigrationBase.location(dialect).substringAfterLast('/'))

    fun fileName(version: Int, name: String): String = "V1_${version}__$name.sql"

    fun versioned(root: File, dialect: Dialect): List<MigrationFile> =
        folder(root, dialect).listFiles().orEmpty()
            .mapNotNull { file ->
                versioned.matchEntire(file.name)?.let { MigrationFile(file, it.groupValues[1].toInt(), it.groupValues[2]) }
            }
            .sortedBy { it.version }

    fun unexpected(root: File, dialect: Dialect): List<String> =
        folder(root, dialect).listFiles().orEmpty()
            .map { it.name }
            .filter { it != baseFile && !versioned.matches(it) }
            .sorted()
}
