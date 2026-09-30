package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dertyp.config.ServerConfig
import dev.dertyp.core.db.Dialect
import dev.dertyp.db.UserTable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Closeable

class DatabaseManager(private val config: ServerConfig) : Closeable {
    private var mainDataSource: HikariDataSource? = null

    fun init() {
        val database = setupDatabase()

        val clientId = config.adminClient.id
        val clientSecret = config.adminClient.secret

        transaction(database) {
            if (clientId != null && clientSecret != null) {
                UserTable.insertIgnore {
                    it[UserTable.username] = clientId
                    it[UserTable.passwordHash] = BCrypt.withDefaults()
                        .hashToString(12, clientSecret.toCharArray())
                    it[UserTable.isAdmin] = true
                }
            }
        }
    }

    fun <T> tempConnection(block: JdbcTransaction.() -> T): T {
        return getDataSource().use { dataSource ->
            val database = Database.connect(dataSource)
            transaction(database) {
                block()
            }
        }
    }

    private fun getDataSource(): HikariDataSource {
        val database = config.database
        val dbDriver = database.driverClassName
        val dbUrl = database.jdbcUrl
        val dbUser = database.user
        val dbPassword = database.password

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = dbUrl
            driverClassName = dbDriver
            
            if (Dialect.ofDriver(dbDriver) == Dialect.SQLITE) {
                maximumPoolSize = 1
                addDataSourceProperty("journal_mode", "WAL")
                addDataSourceProperty("busy_timeout", "5000")
                addDataSourceProperty("foreign_keys", "true")
            } else {
                maximumPoolSize = 100
                username = dbUser
                password = dbPassword
                if (Dialect.ofDriver(dbDriver) == Dialect.POSTGRES) {
                    addDataSourceProperty("options", "-c jit=off")
                }
            }
        }

        return HikariDataSource(hikariConfig)
    }

    private fun setupDatabase(): Database {
        val dataSource = getDataSource()
        mainDataSource = dataSource

        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migrations", "classpath:dev/dertyp/db/migrations")
            .baselineOnMigrate(true)
            .load()

        flyway.migrate()

        return Database.connect(dataSource)
    }

    override fun close() {
        mainDataSource?.close()
    }
}