package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dertyp.db.UserTable
import io.ktor.server.application.ApplicationEnvironment
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class DatabaseManager(private val environment: ApplicationEnvironment) {
    fun init() {
        val database = setupDatabase()

        val clientId = environment.config.propertyOrNull("client.id")?.getString()
        val clientSecret = environment.config.propertyOrNull("client.secret")?.getString()

        transaction(database) {
            if (clientId != null && clientSecret != null) {
                UserTable.insertIgnore {
                    it[UserTable.username] = clientId
                    it[UserTable.passwordHash] = BCrypt.withDefaults()
                        .hashToString(12, clientSecret.toCharArray())
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
        val dbDriver = environment.config.property("storage.driverClassName").getString()
        val dbUrl = environment.config.property("storage.jdbcURL").getString()
        val dbUser = environment.config.property("storage.user").getString()
        val dbPassword = environment.config.property("storage.password").getString()

        val config = HikariConfig().apply {
            jdbcUrl = dbUrl
            driverClassName = dbDriver
            
            if (dbDriver == "org.sqlite.JDBC") {
                maximumPoolSize = 1
                addDataSourceProperty("journal_mode", "WAL")
                addDataSourceProperty("busy_timeout", "5000")
            } else {
                maximumPoolSize = 100
                username = dbUser
                password = dbPassword
            }
        }

        return HikariDataSource(config)
    }

    private fun setupDatabase(): Database {
        val dataSource = getDataSource()

        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migrations", "classpath:dev/dertyp/db/migrations")
            .baselineOnMigrate(true)
            .load()

        flyway.migrate()

        return Database.connect(dataSource)
    }
}