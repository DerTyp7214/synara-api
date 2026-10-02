package dev.dertyp.credentials.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dertyp.credentials.server.DatabaseSettings
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import javax.sql.DataSource

const val SQLITE_DRIVER = "org.sqlite.JDBC"

fun createDataSource(settings: DatabaseSettings): HikariDataSource {
    val hikari = HikariConfig().apply {
        jdbcUrl = settings.jdbcUrl
        driverClassName = settings.driverClassName
        if (settings.driverClassName == SQLITE_DRIVER) {
            maximumPoolSize = 1
            addDataSourceProperty("journal_mode", "WAL")
            addDataSourceProperty("busy_timeout", "5000")
            addDataSourceProperty("foreign_keys", "true")
        } else {
            maximumPoolSize = 10
            username = settings.user
            password = settings.password
        }
    }
    return HikariDataSource(hikari)
}

fun connectDatabase(dataSource: DataSource): Database {
    val database = Database.connect(dataSource)
    transaction(database) {
        SchemaUtils.create(ClientTable, CredentialTable, GrantTable, SigningKeyTable)
    }
    return database
}
