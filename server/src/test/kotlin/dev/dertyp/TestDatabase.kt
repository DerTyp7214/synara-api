package dev.dertyp

import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.sql.DriverManager
import java.sql.Statement
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TestDatabase {
    private const val OWNER_MARK = "synara-test-database"
    private val abandonedAfter = Duration.ofHours(24)
    private val sqliteSuffixes = listOf("", "-wal", "-shm", "-journal")

    private val createdDatabases = ConcurrentHashMap.newKeySet<String>()
    private val createdFiles = ConcurrentHashMap.newKeySet<File>()
    private val deletedFiles = ConcurrentHashMap.newKeySet<File>()

    val postgresContainer: PostgreSQLContainer<*>? by lazy {
        val container = try {
            PostgreSQLContainer("postgres:15-alpine").apply {
                withCommand("postgres", "-c", "max_connections=1000")
                withReuse(true)
                start()
            }
        } catch (e: Exception) {
            println("WARNING: Could not start PostgreSQL testcontainer, falling back to H2. Reason: ${e.message}")
            null
        }
        container?.also(::dropAbandonedDatabases)
    }

    fun getPostgresDbUrl(dbName: String): String {
        val container = postgresContainer ?: return "jdbc:h2:mem:${dbName};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        administer(container) { statement ->
            statement.execute("CREATE DATABASE $dbName")
            createdDatabases += dbName
            statement.execute("COMMENT ON DATABASE $dbName IS '$OWNER_MARK ${System.currentTimeMillis()}'")
        }
        val url = container.jdbcUrl.replace(container.databaseName, dbName)
        return url + (if ('?' in url) "&" else "?") + "options=-c%20jit=off"
    }

    fun connect(dialect: DbDialect, name: String, foreignKeys: Boolean = true): Database {
        return when (dialect) {
            DbDialect.POSTGRES -> {
                val dbName = "${name}_${UUID.randomUUID().toString().replace("-", "")}".lowercase()
                val freshDbUrl = getPostgresDbUrl(dbName)

                val driver = if (postgresContainer != null) "org.postgresql.Driver" else "org.h2.Driver"
                val user = postgresContainer?.username ?: "sa"
                val password = postgresContainer?.password ?: ""

                Database.connect(
                    url = freshDbUrl,
                    driver = driver,
                    user = user,
                    password = password
                )
            }

            DbDialect.SQLITE -> {
                val file = File.createTempFile(name, ".db")
                createdFiles += file
                Database.connect(
                    "jdbc:sqlite:${file.absolutePath}?foreign_keys=$foreignKeys",
                    "org.sqlite.JDBC"
                )
            }
        }
    }

    fun cleanUp() {
        val files = createdFiles.toList()
        files.forEach(::deleteSqliteFile)
        deletedFiles += files
        createdFiles -= files.toSet()

        val names = createdDatabases.toList()
        if (names.isEmpty()) return
        createdDatabases -= names.toSet()
        val container = postgresContainer ?: return
        try {
            administer(container) { statement ->
                names.forEach { name ->
                    try {
                        statement.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)")
                    } catch (e: Exception) {
                        println("WARNING: Could not drop test database $name: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("WARNING: Could not drop the test databases ${names.joinToString()}: ${e.message}")
        }
    }

    fun cleanUpLeftovers(owner: String) {
        val leftovers = createdDatabases.toList() + createdFiles.map { it.name }
        if (leftovers.isNotEmpty()) {
            println(
                "WARNING: $owner finished without TestDatabase.cleanUp() for ${leftovers.size} test database(s), " +
                    "removing them now: ${leftovers.joinToString()}"
            )
        }
        cleanUp()
        deletedFiles.forEach(::deleteSqliteFile)
    }

    private fun deleteSqliteFile(file: File) {
        sqliteSuffixes.forEach { File(file.path + it).delete() }
    }

    private fun administer(container: PostgreSQLContainer<*>, block: (Statement) -> Unit) {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use(block)
        }
    }

    private fun dropAbandonedDatabases(container: PostgreSQLContainer<*>) {
        val createdBefore = System.currentTimeMillis() - abandonedAfter.toMillis()
        try {
            administer(container) { statement ->
                val abandoned = statement.executeQuery(
                    """
                    SELECT datname FROM (
                        SELECT datname, shobj_description(oid, 'pg_database') AS mark FROM pg_database
                    ) marked
                    WHERE mark ~ '^$OWNER_MARK [0-9]+$' AND split_part(mark, ' ', 2)::bigint < $createdBefore
                    """.trimIndent()
                ).use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
                if (abandoned.isEmpty()) return@administer
                println(
                    "WARNING: Dropping ${abandoned.size} test database(s) older than ${abandonedAfter.toHours()} hours " +
                        "that an earlier test run left behind: ${abandoned.joinToString()}"
                )
                abandoned.forEach { statement.execute("DROP DATABASE IF EXISTS $it WITH (FORCE)") }
            }
        } catch (e: Exception) {
            println("WARNING: Could not drop abandoned test databases: ${e.message}")
        }
    }
}
