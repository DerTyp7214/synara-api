package dev.dertyp.core.db

import org.jetbrains.exposed.v1.core.vendors.DatabaseDialect
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.SQLiteDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import java.sql.Connection

enum class Dialect {
    SQLITE,
    POSTGRES,
    OTHER;

    companion object {
        private const val SQLITE_DRIVER = "org.sqlite.JDBC"
        private const val POSTGRES_DRIVER = "org.postgresql.Driver"

        fun current(): Dialect = of(currentDialect)

        fun of(dialect: DatabaseDialect): Dialect = when (dialect) {
            is PostgreSQLDialect -> POSTGRES
            is SQLiteDialect -> SQLITE
            else -> OTHER
        }

        fun of(connection: Connection): Dialect {
            val product = connection.metaData.databaseProductName
            return when {
                product.contains("postgresql", ignoreCase = true) -> POSTGRES
                product.contains("sqlite", ignoreCase = true) -> SQLITE
                else -> OTHER
            }
        }

        fun ofDriver(driverClassName: String): Dialect = when (driverClassName) {
            SQLITE_DRIVER -> SQLITE
            POSTGRES_DRIVER -> POSTGRES
            else -> OTHER
        }
    }
}
