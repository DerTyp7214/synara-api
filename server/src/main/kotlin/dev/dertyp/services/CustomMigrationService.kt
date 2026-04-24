package dev.dertyp.services

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.CustomMigrationTable
import dev.dertyp.dbQuery
import io.ktor.util.logging.KtorSimpleLogger
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.core.component.KoinComponent
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLDecoder
import java.util.jar.JarFile
import kotlin.system.measureTimeMillis

class CustomMigrationService : KoinComponent {
    private val logger = KtorSimpleLogger("CustomMigrationService")

    suspend fun runMigrations(migrations: List<CustomMigration>? = null) {
        val discoveredMigrations = migrations ?: scanForMigrations()
        val sortedMigrations = discoveredMigrations.sortedWith { a, b ->
            compareVersions(a.version, b.version)
        }
        if (sortedMigrations.isEmpty()) return

        logger.info("Found ${sortedMigrations.size} custom migrations.")

        val executed = dbQuery {
            CustomMigrationTable.selectAll()
                .map { it[CustomMigrationTable.id] }
                .toSet()
        }

        sortedMigrations.forEach { migration ->
            if (migration.id !in executed) {
                logger.info("Starting custom migration ${migration.version}: ${migration.id}")
                val time = measureTimeMillis {
                    migration.migrate()
                }
                logger.info("Finished custom migration ${migration.id} in ${formatDuration(time)}")
                dbQuery {
                    CustomMigrationTable.insert {
                        it[id] = migration.id
                        it[executedAt] = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    private fun scanForMigrations(): List<CustomMigration> {
        val packageName = "dev.dertyp.migrations.custom"
        val path = packageName.replace('.', '/')
        val classLoader = this::class.java.classLoader
        val resources = classLoader.getResources(path)
        val migrations = mutableListOf<CustomMigration>()

        for (resource in resources.asSequence()) {
            when (resource.protocol) {
                "file" -> {
                    File(resource.toURI()).walk()
                        .filter { it.extension == "class" }
                        .forEach { file ->
                            val relativePath = file.absolutePath.replace(File.separatorChar, '/')
                            val className = relativePath
                                .substringAfter(path)
                                .removeSuffix(".class")
                                .replace('/', '.')
                            val fullClassName = "$packageName${if (className.startsWith('.')) "" else "."}$className"
                            loadMigration(fullClassName)?.let { migrations.add(it) }
                        }
                }
                "jar" -> {
                    val rawPath = resource.path
                    val jarPath = if (rawPath.startsWith("file:")) {
                        rawPath.substring(5, rawPath.indexOf("!"))
                    } else {
                        rawPath.substring(0, rawPath.indexOf("!"))
                    }
                    JarFile(URLDecoder.decode(jarPath, "UTF-8")).use { jar ->
                        jar.entries().asSequence()
                            .filter { it.name.startsWith(path) && it.name.endsWith(".class") }
                            .forEach { entry ->
                                val className = entry.name.removeSuffix(".class").replace('/', '.')
                                loadMigration(className)?.let { migrations.add(it) }
                            }
                    }
                }
            }
        }
        return migrations
    }

    private fun loadMigration(className: String): CustomMigration? {
        return try {
            val clazz = Class.forName(className)
            if (CustomMigration::class.java.isAssignableFrom(clazz) &&
                clazz.isAnnotationPresent(Migration::class.java) &&
                !clazz.isInterface &&
                !Modifier.isAbstract(clazz.modifiers)) {
                clazz.getDeclaredConstructor().newInstance() as CustomMigration
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split('.', '_').mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split('.', '_').mapNotNull { it.toIntOrNull() }

        val size = maxOf(parts1.size, parts2.size)
        for (i in 0 until size) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    private fun formatDuration(ms: Long): String {
        if (ms < 1000) return "${ms}ms"

        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60))

        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0 || hours > 0) append("${minutes}m ")
            append("${seconds}s")
        }
    }
}
