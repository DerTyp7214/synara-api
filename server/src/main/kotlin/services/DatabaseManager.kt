package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dertyp.db.UserTable
import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction

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

    fun <T> tempConnection(block: Database.() -> T): T {
        val dataSource = getDataSource()
        val database = Database.connect(dataSource)

        val result = transaction(database) {
            database.block()
        }

        dataSource.close()
        return result
    }

    private fun getDataSource(): HikariDataSource {
        val dbDriver = environment.config.property("storage.driverClassName").getString()
        val dbUrl = environment.config.property("storage.jdbcURL").getString()
        val dbUser = environment.config.property("storage.user").getString()
        val dbPassword = environment.config.property("storage.password").getString()

        val config = HikariConfig().apply {
            jdbcUrl = dbUrl
            driverClassName = dbDriver
            if (dbDriver != "org.sqlite.JDBC") {
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
            .load()

        flyway.migrate()

        return Database.connect(dataSource)
    }
}