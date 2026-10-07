package dev.dertyp

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dertyp.core.db.Dialect
import dev.dertyp.db.MigrationBase
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.TestcontainersConfiguration
import java.io.File
import java.net.InetAddress
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TestDatabase {
    private const val OWNER_MARK = "synara-test-database"
    private const val IMAGE = "postgres:15-alpine"
    private const val POOL_SIZE = 10
    private const val ADMIN_VALIDATION_SECONDS = 1
    private const val COPY_STRATEGY = "FILE_COPY"
    private const val SHARED_MEMORY_BYTES = 256L * 1024 * 1024
    private const val START_LOCK_FILE = "synara-test-postgres.lock"
    private const val OWNED_MARK_PARTS = 5
    private const val PROCESS_START_FIELD = 19
    private val bootId = Path.of("/proc/sys/kernel/random/boot_id")
    private val processNamespace = Path.of("/proc/self/ns/pid")
    private val unsafeMarkCharacters = Regex("[^A-Za-z0-9@._-]")
    private val settings = listOf("max_connections=1000", "fsync=off", "synchronous_commit=off", "full_page_writes=off")
    private val abandonedAfter = Duration.ofHours(24)
    private val sqliteSuffixes = listOf("", "-wal", "-shm", "-journal")
    private val jvmMark = UUID.randomUUID().toString().take(8)
    private val readsProcessFiles = Files.isReadable(bootId)
    private val origin by lazy {
        try {
            val machine = if (readsProcessFiles) {
                Files.readString(bootId).trim() + "." + Files.readSymbolicLink(processNamespace).toString().filter(Char::isDigit)
            } else {
                InetAddress.getLocalHost().hostName
            }
            "${System.getProperty("user.name")}@$machine".replace(unsafeMarkCharacters, "_")
        } catch (e: Exception) {
            println("WARNING: Could not tell which host owns the test databases, they carry no owner process: ${e.message}")
            null
        }
    }
    private val ownerMark by lazy {
        val pid = ProcessHandle.current().pid()
        val start = processStart(pid)
        if (origin == null || start == null) "" else " $origin $pid $start"
    }

    private val createdDatabases = ConcurrentHashMap.newKeySet<String>()
    private val createdTemplates = ConcurrentHashMap.newKeySet<String>()
    private val templatesByTables = HashMap<TableList, String>()
    private val templatesByKey = HashMap<String, String>()
    private val createdPools = ConcurrentHashMap<String, HikariDataSource>()
    private val createdFiles = ConcurrentHashMap.newKeySet<File>()
    private val deletedFiles = ConcurrentHashMap.newKeySet<File>()
    private val adminLock = Any()
    private var adminConnection: Connection? = null

    private val postgresStart by lazy {
        TestContainers.start(
            service = "PostgreSQL",
            image = IMAGE,
            version = ::serverVersion,
        ) {
            PostgreSQLContainer(IMAGE).apply {
                withCommand("postgres", *settings.flatMap { listOf("-c", it) }.toTypedArray())
                withTmpFs(mapOf("/var/lib/postgresql/data" to "rw,size=2g"))
                withSharedMemorySize(SHARED_MEMORY_BYTES)
                withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                withReuse(true)
                if (TestcontainersConfiguration.getInstance().environmentSupportsReuse()) startOneForAllJvms(this) else start()
            }
        }.onSuccess(::dropAbandonedDatabases).also { closeAdminConnection() }
    }

    private fun startOneForAllJvms(container: PostgreSQLContainer<*>) {
        val lockFile = Path.of(System.getProperty("java.io.tmpdir"), START_LOCK_FILE)
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { container.start() }
        }
    }

    val postgresContainer: PostgreSQLContainer<*>
        get() {
            TestContainers.assumeEnabled()
            return postgresStart.getOrThrow()
        }

    fun getPostgresDbUrl(dbName: String): String = postgresDbUrl(dbName, template = null)

    fun getMigratedPostgresDbUrl(dbName: String, upTo: String? = null): String =
        postgresDbUrl(dbName, migratedTemplate(upTo))

    fun connect(dialect: DbDialect, name: String, vararg tables: Table, foreignKeys: Boolean = true): Database {
        return when (dialect) {
            DbDialect.POSTGRES -> connectPostgres(name, if (tables.isEmpty()) null else schemaTemplate(tables.asList()))

            DbDialect.SQLITE -> connectSqlite(name, foreignKeys).also { database ->
                if (tables.isNotEmpty()) transaction(database) { SchemaUtils.create(*tables) }
            }
        }
    }

    fun connectMigrated(dialect: DbDialect, name: String): Database {
        return when (dialect) {
            DbDialect.POSTGRES -> connectPostgres(name, migratedTemplate(upTo = null))

            DbDialect.SQLITE -> connectSqlite(name, foreignKeys = true).also { database ->
                migrate(Dialect.SQLITE, database.url, user = "", password = "", upTo = null)
            }
        }
    }

    private fun connectPostgres(name: String, template: String?): Database {
        val dbName = "${name}_${UUID.randomUUID().toString().replace("-", "")}".lowercase()
        val freshDbUrl = postgresDbUrl(dbName, template)
        val container = postgresContainer
        val pool = HikariDataSource(HikariConfig().apply {
            poolName = dbName
            jdbcUrl = freshDbUrl
            username = container.username
            password = container.password
            maximumPoolSize = POOL_SIZE
            minimumIdle = 1
        })
        createdPools[dbName] = pool
        return Database.connect(pool)
    }

    private fun connectSqlite(name: String, foreignKeys: Boolean): Database {
        val file = File.createTempFile(name, ".db")
        createdFiles += file
        return Database.connect(
            "jdbc:sqlite:${file.absolutePath}?foreign_keys=$foreignKeys",
            "org.sqlite.JDBC"
        )
    }

    private fun postgresDbUrl(dbName: String, template: String?): String {
        createDatabase(dbName, template, createdDatabases)
        return urlOf(dbName)
    }

    private fun createDatabase(dbName: String, template: String?, created: MutableSet<String>) {
        administer(postgresContainer) { statement ->
            statement.execute("CREATE DATABASE $dbName" + template?.let { " TEMPLATE $it" }.orEmpty() + " STRATEGY $COPY_STRATEGY")
            created += dbName
            statement.execute("COMMENT ON DATABASE $dbName IS '$OWNER_MARK ${System.currentTimeMillis()}$ownerMark'")
        }
    }

    private fun urlOf(dbName: String): String {
        val container = postgresContainer
        val url = container.jdbcUrl.replace(container.databaseName, dbName)
        return url + (if ('?' in url) "&" else "?") + "options=-c%20jit=off"
    }

    @Synchronized
    private fun schemaTemplate(tables: List<Table>): String = templatesByTables.getOrPut(TableList(tables)) {
        val statements = createStatements(tables)
        templatesByKey.getOrPut(ddlKey(statements)) {
            val container = postgresContainer
            val template = newTemplate()
            DriverManager.getConnection(urlOf(template), container.username, container.password).use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { statement ->
                    statements.forEach(statement::execute)
                    connection.commit()
                    val filled = filledTables(statement)
                    check(filled.isEmpty()) {
                        "The schema template $template holds rows before any test used it, in: ${filled.joinToString()}"
                    }
                }
            }
            closeToConnections(template)
            template
        }
    }

    private fun createStatements(tables: List<Table>): List<String> {
        val container = postgresContainer
        val database = Database.connect(container.jdbcUrl, user = container.username, password = container.password)
        return try {
            transaction(database) { SchemaUtils.createStatements(*tables.toTypedArray()) }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    private fun filledTables(statement: Statement): List<String> = statement.executeQuery(
        """
        SELECT name FROM (
            SELECT n.nspname || '.' || c.relname AS name,
                (xpath('/row/filled/text()', query_to_xml(
                    format('SELECT EXISTS (SELECT 1 FROM %I.%I) AS filled', n.nspname, c.relname), false, true, ''
                )))[1]::text AS filled
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'
        ) checked
        WHERE filled = 'true'
        ORDER BY name
        """.trimIndent()
    ).use { rows ->
        buildList { while (rows.next()) add(rows.getString(1)) }
    }

    @Synchronized
    private fun migratedTemplate(upTo: String?): String = templatesByKey.getOrPut("migrated to ${upTo ?: "head"}") {
        val container = postgresContainer
        val template = newTemplate()
        migrate(Dialect.POSTGRES, urlOf(template), container.username, container.password, upTo)
        closeToConnections(template)
        template
    }

    private fun closeToConnections(template: String) {
        administer(postgresContainer) { statement ->
            statement.execute("ALTER DATABASE $template WITH ALLOW_CONNECTIONS false")
        }
    }

    private fun newTemplate(): String {
        val template = "template_${jvmMark}_${UUID.randomUUID().toString().take(8)}"
        createDatabase(template, template = null, createdTemplates)
        return template
    }

    private fun ddlKey(statements: List<String>): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(statements.joinToString("\n").toByteArray()))

    private fun migrate(dialect: Dialect, url: String, user: String, password: String, upTo: String?) {
        val configuration = Flyway.configure()
            .dataSource(url, user, password)
            .locations(MigrationBase.location(dialect))
            .placeholderReplacement(false)
        if (upTo != null) configuration.target(upTo)
        configuration.load().migrate()
    }

    fun cleanUp() {
        val files = createdFiles.toList()
        files.forEach(::deleteSqliteFile)
        deletedFiles += files
        createdFiles -= files.toSet()

        createdPools.keys.toList().forEach { name -> createdPools.remove(name)?.close() }

        val names = createdDatabases.toList()
        createdDatabases -= names.toSet()
        dropDatabases(names)
    }

    fun shutDown() {
        val names = createdTemplates.toList()
        createdTemplates -= names.toSet()
        synchronized(this) {
            templatesByTables.clear()
            templatesByKey.clear()
        }
        dropDatabases(names)
        closeAdminConnection()
    }

    private fun dropDatabases(names: List<String>) {
        if (names.isEmpty()) return
        val container = postgresStart.getOrThrow()
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

    private fun serverVersion(container: PostgreSQLContainer<*>): String {
        var version = ""
        administer(container) { statement ->
            statement.executeQuery("SHOW server_version").use { rows ->
                rows.next()
                version = rows.getString(1)
            }
        }
        return version
    }

    private fun administer(container: PostgreSQLContainer<*>, block: (Statement) -> Unit) {
        synchronized(adminLock) {
            val connection = adminConnection?.takeIf { it.isValid(ADMIN_VALIDATION_SECONDS) } ?: run {
                closeAdminConnection()
                DriverManager.getConnection(container.jdbcUrl, container.username, container.password).also { adminConnection = it }
            }
            connection.createStatement().use(block)
        }
    }

    private fun closeAdminConnection() {
        synchronized(adminLock) {
            val connection = adminConnection
            adminConnection = null
            try {
                connection?.close()
            } catch (e: Exception) {
                println("WARNING: Could not close the administrative test database connection: ${e.message}")
            }
        }
    }

    private fun dropAbandonedDatabases(container: PostgreSQLContainer<*>) {
        val createdBefore = System.currentTimeMillis() - abandonedAfter.toMillis()
        try {
            administer(container) { statement ->
                val abandoned = statement.executeQuery(
                    """
                    SELECT datname, mark FROM (
                        SELECT datname, shobj_description(oid, 'pg_database') AS mark FROM pg_database
                    ) marked
                    WHERE mark ~ '^$OWNER_MARK [0-9]+( |$)'
                    """.trimIndent()
                ).use { rows ->
                    buildList { while (rows.next()) if (isAbandoned(rows.getString(2), createdBefore)) add(rows.getString(1)) }
                }
                if (abandoned.isEmpty()) return@administer
                println(
                    "WARNING: Dropping ${abandoned.size} test database(s) that an earlier test run left behind, their test JVM " +
                        "is gone or they are older than ${abandonedAfter.toHours()} hours: ${abandoned.joinToString()}"
                )
                abandoned.forEach { statement.execute("DROP DATABASE IF EXISTS $it WITH (FORCE)") }
            }
        } catch (e: Exception) {
            println("WARNING: Could not drop abandoned test databases: ${e.message}")
        }
    }

    private fun isAbandoned(mark: String, createdBefore: Long): Boolean {
        val parts = mark.split(' ')
        val created = parts[1].toLongOrNull() ?: return false
        val pid = parts.getOrNull(3)?.toLongOrNull()
        if (parts.size == OWNED_MARK_PARTS && pid != null && parts[2] == origin) {
            if (ProcessHandle.of(pid).isEmpty) return true
            processStart(pid)?.let { return it != parts[4] }
        }
        return created < createdBefore
    }

    private fun processStart(pid: Long): String? = try {
        if (readsProcessFiles) {
            "t" + Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(')').trim().split(' ')[PROCESS_START_FIELD]
        } else {
            ProcessHandle.of(pid).flatMap { it.info().startInstant() }.map { "m${it.toEpochMilli()}" }.orElse(null)
        }
    } catch (e: Exception) {
        null
    }

    private class TableList(private val tables: List<Table>) {
        override fun equals(other: Any?): Boolean =
            other is TableList && tables.size == other.tables.size && tables.indices.all { tables[it] === other.tables[it] }

        override fun hashCode(): Int = tables.fold(1) { hash, table -> 31 * hash + System.identityHashCode(table) }
    }
}
