package dev.dertyp.core.db

import dev.dertyp.db.MigrationBase
import io.ktor.util.logging.KtorSimpleLogger
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

class SqliteForeignKeyCheck {
    private val logger = KtorSimpleLogger("SqliteForeignKeyCheck")

    suspend fun run(): Map<String, Int> = dbQuery {
        if (Dialect.current() != Dialect.SQLITE) return@dbQuery emptyMap()

        logger.info(
            "SQLite databases that were not created from the migration base ${MigrationBase.VERSION} " +
                "keep their previous foreign key rules on disk"
        )

        val violations = sortedMapOf<String, Int>()
        TransactionManager.current().exec(FOREIGN_KEY_CHECK) { rs ->
            while (rs.next()) violations.merge(rs.getString(1), 1, Int::plus)
        }

        if (violations.isNotEmpty()) {
            val summary = violations.entries.joinToString { (table, count) -> "$table: $count" }
            logger.warn("SQLite foreign key check found ${violations.values.sum()} violation(s): $summary")
        }
        violations
    }

    companion object {
        private const val FOREIGN_KEY_CHECK = "PRAGMA foreign_key_check"
    }
}
