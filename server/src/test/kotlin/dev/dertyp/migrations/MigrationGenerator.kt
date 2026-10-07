package dev.dertyp.migrations

import dev.dertyp.TestDatabase
import dev.dertyp.core.db.Dialect
import java.io.File
import java.net.URI
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.system.exitProcess

private const val POSTGRES_DRIVER = "org.postgresql.Driver"
private const val SQLITE_DRIVER = "org.sqlite.JDBC"

private class GeneratorFailure(message: String) : Exception(message)

private class PostgresServer(val url: String, val user: String, val password: String) {
    fun <T> withScratchDatabase(block: (MigrationDatabase) -> T): T {
        val name = "generate_migration_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = URI(url.removePrefix("jdbc:"))
        val scratch = URI(admin.scheme, admin.userInfo, admin.host, admin.port, "/$name", null, null)
        try {
            execute("CREATE DATABASE $name")
        } catch (error: SQLException) {
            throw GeneratorFailure("Could not create a scratch database on $url, nothing was written: ${error.message}")
        }
        try {
            return block(MigrationDatabase(Dialect.POSTGRES, POSTGRES_DRIVER, "jdbc:$scratch", user, password))
        } finally {
            execute("DROP DATABASE $name WITH (FORCE)")
        }
    }

    private fun execute(statement: String) {
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }
}

private fun postgresServer(): PostgresServer {
    System.getProperty("postgresUrl")?.takeIf { it.isNotBlank() }?.let { url ->
        return PostgresServer(url, System.getProperty("postgresUser").orEmpty(), System.getProperty("postgresPassword").orEmpty())
    }
    val container = runCatching { TestDatabase.postgresContainer }.getOrNull() ?: throw GeneratorFailure(
        "No PostgreSQL available, nothing was written. Start Docker so the postgres:15-alpine test container can run, " +
            "or pass a server with -PpostgresUrl=jdbc:postgresql://host:port/postgres -PpostgresUser=... " +
            "-PpostgresPassword=... (a scratch database is created on it and dropped again)."
    )
    return PostgresServer(container.jdbcUrl, container.username, container.password)
}

private fun <T> withSqliteDatabase(block: (MigrationDatabase) -> T): T {
    val file = File.createTempFile("generate_migration", ".db")
    try {
        return block(MigrationDatabase(Dialect.SQLITE, SQLITE_DRIVER, "jdbc:sqlite:${file.absolutePath}", "", ""))
    } finally {
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }
}

private fun generate(root: File, name: String) {
    if (!Regex("[A-Za-z][A-Za-z0-9]*").matches(name)) {
        throw GeneratorFailure("Pass the migration name as -Pname=AddSomething (letters and digits, starting with a letter).")
    }
    val dialects = listOf(Dialect.POSTGRES, Dialect.SQLITE)
    val existing = dialects.associateWith { dialect -> MigrationFiles.versioned(root, dialect).map { it.version to it.name } }
    if (existing.values.distinct().size > 1) {
        throw GeneratorFailure("The postgres and sqlite migration folders do not hold the same migrations, nothing was written.")
    }
    val version = (existing.values.flatten().map { it.first } + MigrationFiles.baseVersion).max() + 1
    val targets = dialects.associateWith { File(MigrationFiles.folder(root, it), MigrationFiles.fileName(version, name)) }
    targets.values.firstOrNull { it.exists() }?.let {
        throw GeneratorFailure("$it exists already, nothing was written.")
    }

    val postgres = postgresServer()
    val states = mapOf(
        Dialect.POSTGRES to postgres.withScratchDatabase(SchemaDrift::inspect),
        Dialect.SQLITE to withSqliteDatabase(SchemaDrift::inspect),
    )

    states.forEach { (dialect, state) ->
        state.tolerated.groupBy { it.cause }.forEach { (cause, differences) ->
            println("$dialect: ${differences.size} statement(s) of Exposed left out, $cause: ${cause.reason}")
            differences.forEach { println("  ${it.statement};") }
        }
    }

    if (states.values.all { it.pending.isEmpty() }) {
        println("Nothing to do: the table definitions match the migrations on PostgreSQL and SQLite. No file was written.")
        return
    }

    states.forEach { (dialect, state) ->
        val target = targets.getValue(dialect)
        target.writeText(state.pending.joinToString("") { it.trim().removeSuffix(";") + ";\n" })
        println("$dialect: ${state.pending.size} statement(s) written to $target")
        if (state.pending.isEmpty()) println("  The file is empty so that both folders hold the same versions.")
        state.pending.forEach { println("  $it;") }
        state.warnings.forEach { println("  WARNING ${it.describe()}") }
        if (state.pendingTablesWithTriggers.isNotEmpty()) {
            println(
                "  These tables have triggers: ${state.pendingTablesWithTriggers.joinToString()}. " +
                    "Exposed does not generate triggers, functions or expression indexes. " +
                    "Append what this change needs to $target by hand."
            )
        }
    }
}

fun main(args: Array<String>) {
    try {
        generate(File(args[0]), args.getOrElse(1) { "" })
    } catch (failure: GeneratorFailure) {
        System.err.println(failure.message)
        exitProcess(1)
    }
}
