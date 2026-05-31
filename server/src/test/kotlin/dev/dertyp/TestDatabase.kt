package dev.dertyp

import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.sql.DriverManager
import java.util.UUID

object TestDatabase {
    private var currentFile: File? = null
    private var currentDbName: String? = null
    
    val postgresContainer: PostgreSQLContainer<*>? by lazy {
        try {
            PostgreSQLContainer("postgres:15-alpine").apply {
                withCommand("postgres", "-c", "max_connections=1000")
                withReuse(true)
                start()
            }
        } catch (e: Exception) {
            println("WARNING: Could not start PostgreSQL testcontainer, falling back to H2. Reason: ${e.message}")
            null
        }
    }

    fun getPostgresDbUrl(dbName: String): String {
        val container = postgresContainer ?: return "jdbc:h2:mem:${dbName};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(
            container.jdbcUrl,
            container.username,
            container.password
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE DATABASE $dbName")
            }
        }
        return container.jdbcUrl.replace(container.databaseName, dbName)
    }

    fun connect(dialect: DbDialect, name: String): Database {
        return when (dialect) {
            DbDialect.POSTGRES -> {
                val dbName = "${name}_${UUID.randomUUID().toString().replace("-", "")}".lowercase()
                currentDbName = dbName
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
                currentFile = File.createTempFile(name, ".db")
                Database.connect("jdbc:sqlite:${currentFile!!.absolutePath}", "org.sqlite.JDBC")
            }
        }
    }

    fun cleanUp() {
        currentFile?.delete()
        currentFile = null

        currentDbName?.let { dbName ->
            postgresContainer?.let { container ->
                try {
                    DriverManager.getConnection(
                        container.jdbcUrl,
                        container.username,
                        container.password
                    ).use { conn ->
                        conn.createStatement().use { stmt ->
                            stmt.execute(
                                """
                                SELECT pg_terminate_backend(pg_stat_activity.pid)
                                FROM pg_stat_activity
                                WHERE pg_stat_activity.datname = '$dbName'
                                AND pid <> pg_backend_pid();
                                """.trimIndent()
                            )
                            stmt.execute("DROP DATABASE $dbName")
                        }
                    }
                } catch (e: Exception) {
                    println("WARNING: Could not drop test database $dbName: ${e.message}")
                }
            }
        }
        currentDbName = null
    }
}
