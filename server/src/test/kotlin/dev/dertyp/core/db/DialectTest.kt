package dev.dertyp.core.db

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.sql.Connection

class DialectTest {

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    private fun expected(dialect: DbDialect): Dialect = when (dialect) {
        DbDialect.SQLITE -> Dialect.SQLITE
        DbDialect.POSTGRES -> if (TestDatabase.postgresContainer != null) Dialect.POSTGRES else Dialect.OTHER
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `every context resolves the same dialect`(dialect: DbDialect) {
        val database = TestDatabase.connect(dialect, "dialect_test")
        val expected = expected(dialect)

        transaction(database) {
            val jdbc = connection.connection as Connection
            assertEquals(expected, Dialect.current())
            assertEquals(expected, Dialect.of(db.dialect))
            assertEquals(expected, Dialect.of(jdbc))
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the helper matches every legacy check`(dialect: DbDialect) {
        val database = TestDatabase.connect(dialect, "dialect_legacy_test")

        transaction(database) {
            val jdbc = connection.connection as Connection
            val resolved = Dialect.of(jdbc)
            val product = jdbc.metaData.databaseProductName
            val driver = jdbc.metaData.driverName

            assertEquals(driver.contains("sqlite", ignoreCase = true), resolved == Dialect.SQLITE)
            assertEquals(product.contains("PostgreSQL", ignoreCase = true), resolved == Dialect.POSTGRES)
            assertEquals(product.lowercase().contains("postgresql"), resolved == Dialect.POSTGRES)
            assertEquals(db.dialect.name.contains("postgres", ignoreCase = true), Dialect.current() == Dialect.POSTGRES)
            assertEquals(currentDialect is PostgreSQLDialect, Dialect.current() == Dialect.POSTGRES)
            assertEquals(false, driver == "org.sqlite.JDBC")
        }
    }

    @Test
    fun `driver class names resolve to their dialect`() {
        assertEquals(Dialect.SQLITE, Dialect.ofDriver("org.sqlite.JDBC"))
        assertEquals(Dialect.POSTGRES, Dialect.ofDriver("org.postgresql.Driver"))
        assertEquals(Dialect.OTHER, Dialect.ofDriver("org.h2.Driver"))
    }
}
