package dev.dertyp.migrations

import dev.dertyp.config.ServerConfig
import dev.dertyp.core.db.Dialect
import dev.dertyp.core.db.SchemaTables
import dev.dertyp.db.SearchIndexQueueTable
import dev.dertyp.db.SongAudioTimelineTable
import dev.dertyp.services.DatabaseManager
import io.ktor.server.config.MapApplicationConfig
import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.Index
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

class MigrationDatabase(
    val dialect: Dialect,
    val driver: String,
    val url: String,
    val user: String,
    val password: String,
)

enum class DifferenceCause(val reason: String) {
    INEXPRESSIBLE_INDEX(
        "Index of the migrated schema with an access method other than btree or over an expression, " +
            "which no table definition can declare, Exposed would drop it"
    ),
    DEFAULT_SPELLING(
        "The catalog stores the declared default as a cast literal ('-70.0'::numeric), Exposed compares the text. " +
            "Explicit because the catalog holds only that text, no property separates it from a different default"
    ),
}

class ToleratedDifference(val table: Table, val statement: String, val cause: DifferenceCause)

enum class DataRisk(val advice: String) {
    POSSIBLE_RENAME(
        "The table gains and loses columns in the same migration. If this is a rename, the generated statements " +
            "add an empty column and drop the old one with its data. " +
            "Replace them by ALTER TABLE ... RENAME COLUMN ... TO ... in both files"
    ),
    REQUIRED_WITHOUT_DEFAULT(
        "The new column is NOT NULL and has no database default. Adding it fails on PostgreSQL once the table " +
            "has rows and always on SQLite. Give it a default, or add it nullable and fill it"
    ),
}

class DataRiskWarning(val table: Table, val risk: DataRisk, val added: List<String>, val removed: List<String>) {
    fun describe(): String = buildString {
        append("${table.tableName}: ")
        if (added.isNotEmpty()) append("added ${added.joinToString()}. ")
        if (removed.isNotEmpty()) append("removed ${removed.joinToString()}. ")
        append(risk.advice)
    }
}

class SchemaState(
    val required: List<String>,
    val tolerated: List<ToleratedDifference>,
    val pendingTablesWithTriggers: List<String>,
    val warnings: List<DataRiskWarning>,
) {
    val pending: List<String> = required - tolerated.map { it.statement }.toSet()
}

object SchemaDrift {
    private val postgresOnlyTables: Set<Table> = setOf(SearchIndexQueueTable)

    val explicitDifferences: Map<Dialect, List<ToleratedDifference>> = mapOf(
        Dialect.POSTGRES to listOf(
            ToleratedDifference(
                SongAudioTimelineTable,
                "ALTER TABLE song_audio_timeline ALTER COLUMN \"envelopeMinDb\" SET DEFAULT -70.0",
                DifferenceCause.DEFAULT_SPELLING,
            )
        )
    )

    fun tables(dialect: Dialect): List<Table> =
        if (dialect == Dialect.POSTGRES) SchemaTables.all else SchemaTables.all - postgresOnlyTables

    fun manager(database: MigrationDatabase): DatabaseManager = DatabaseManager(
        ServerConfig(
            MapApplicationConfig(
                "storage.driverClassName" to database.driver,
                "storage.jdbcURL" to database.url,
                "storage.user" to database.user,
                "storage.password" to database.password,
            )
        )
    )

    fun inspect(database: MigrationDatabase, additionalTables: List<Table> = emptyList()): SchemaState =
        inspectEach(database, listOf(additionalTables)).single()

    fun inspectEach(
        database: MigrationDatabase,
        additions: List<List<Table>>,
        indicesOnlyInDatabase: List<Index> = emptyList(),
    ): List<SchemaState> =
        manager(database).use { manager ->
            manager.init()
            manager.tempConnection {
                additions.map { state(database.dialect, tables(database.dialect) + it) } +
                    indicesOnlyInDatabase.map { stateWith(it, database.dialect) }
            }
        }

    private fun JdbcTransaction.stateWith(index: Index, dialect: Dialect): SchemaState {
        index.createStatement().forEach { exec(it) }
        connection.metadata { cleanCache() }
        val state = state(dialect, tables(dialect))
        index.dropStatement().forEach { exec(it) }
        connection.metadata { cleanCache() }
        return state
    }

    private fun JdbcTransaction.state(dialect: Dialect, tables: List<Table>): SchemaState {
        val required = MigrationUtils
            .statementsRequiredForDatabaseMigration(*tables.toTypedArray(), withLogs = false)
            .distinct()
        val tolerated = toleratedIndexes(dialect, tables) + explicitDifferences[dialect].orEmpty()
        val known = tolerated.map { it.statement }.toSet()
        val withTriggers = if (required.all { it in known }) emptyList() else {
            val triggerTables = tablesWithTriggers(dialect)
            tables
                .filter { it.tableName.lowercase() in triggerTables }
                .filter { table ->
                    MigrationUtils.statementsRequiredForDatabaseMigration(table, withLogs = false)
                        .any { it !in known }
                }
                .map { it.tableName }
        }
        return SchemaState(required, tolerated, withTriggers, dataRiskWarnings(tables))
    }

    private fun JdbcTransaction.dataRiskWarnings(tables: List<Table>): List<DataRiskWarning> {
        val migrated = tables.filter { it.exists() }
        val existing = currentDialectMetadata.tableColumns(*migrated.toTypedArray())
        return migrated.flatMap { table ->
            val present = existing[table].orEmpty().map { it.name }
            val added = table.columns.filter { column -> present.none { it.equals(column.nameUnquoted(), true) } }
            val removed = present.filter { name -> table.columns.none { it.nameUnquoted().equals(name, true) } }
            val required = added.filter {
                !it.columnType.nullable && it.defaultValueInDb() == null && it.columnType !is AutoIncColumnType<*>
            }
            buildList {
                if (added.isNotEmpty() && removed.isNotEmpty()) {
                    add(DataRiskWarning(table, DataRisk.POSSIBLE_RENAME, added.map { it.name }, removed))
                }
                if (required.isNotEmpty()) {
                    add(DataRiskWarning(table, DataRisk.REQUIRED_WITHOUT_DEFAULT, required.map { it.name }, emptyList()))
                }
            }
        }
    }

    private fun JdbcTransaction.toleratedIndexes(dialect: Dialect, tables: List<Table>): List<ToleratedDifference> {
        val inexpressible = inexpressibleIndexNames(dialect)
        val migrated = tables.filter { it.exists() }
        return currentDialectMetadata.existingIndices(*migrated.toTypedArray()).flatMap { (table, existing) ->
            existing
                .filter { it.indexName in inexpressible }
                .flatMap { it.dropStatement() }
                .map { ToleratedDifference(table, it, DifferenceCause.INEXPRESSIBLE_INDEX) }
        }
    }

    private fun JdbcTransaction.inexpressibleIndexNames(dialect: Dialect): Set<String> {
        if (dialect != Dialect.POSTGRES) return emptySet()
        return exec(
            "SELECT c.relname FROM pg_index i " +
                "JOIN pg_class c ON c.oid = i.indexrelid " +
                "JOIN pg_am a ON a.oid = c.relam " +
                "WHERE c.relnamespace = current_schema()::regnamespace " +
                "AND (a.amname <> 'btree' OR i.indexprs IS NOT NULL)"
        ) { rows ->
            buildSet { while (rows.next()) add(rows.getString(1)) }
        }.orEmpty()
    }

    private fun JdbcTransaction.tablesWithTriggers(dialect: Dialect): Set<String> {
        val query = when (dialect) {
            Dialect.POSTGRES ->
                "SELECT c.relname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid WHERE NOT t.tgisinternal"
            Dialect.SQLITE -> "SELECT tbl_name FROM sqlite_master WHERE type = 'trigger'"
            Dialect.OTHER -> return emptySet()
        }
        return exec(query) { rows ->
            buildSet { while (rows.next()) add(rows.getString(1).lowercase()) }
        }.orEmpty()
    }
}
