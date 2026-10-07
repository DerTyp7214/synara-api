package dev.dertyp.migrations

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.db.Dialect
import dev.dertyp.db.CustomMigrationTable
import dev.dertyp.db.HueBridgeTable
import org.jetbrains.exposed.v1.core.Index
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.UUID

private object UnmigratedTable : Table("drift_probe") {
    val probeId = integer("probeId")
    val label = varchar("label", 32).nullable()

    override val primaryKey = PrimaryKey(probeId)
}

private object CustomMigrationWithUnmigratedColumn : Table(CustomMigrationTable.tableName) {
    val id = varchar(CustomMigrationTable.id.name, 255)
    val executedAt = long(CustomMigrationTable.executedAt.name)
    val unmigrated = integer("driftProbe").default(0)

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, unmigrated)
    }
}

private object CustomMigrationWithRenamedColumn : Table(CustomMigrationTable.tableName) {
    val id = varchar(CustomMigrationTable.id.name, 255)
    val finishedAt = long("finishedAt").default(0)

    override val primaryKey = PrimaryKey(id)
}

private object CustomMigrationWithRequiredColumn : Table(CustomMigrationTable.tableName) {
    val id = varchar(CustomMigrationTable.id.name, 255)
    val executedAt = long(CustomMigrationTable.executedAt.name)
    val required = integer("requiredProbe")
    val optional = integer("optionalProbe").nullable()
    val defaulted = integer("defaultedProbe").default(0)

    override val primaryKey = PrimaryKey(id)
}

class SchemaDriftTest {
    private val files = mutableListOf<File>()

    @AfterEach
    fun tearDown() {
        files.forEach { file ->
            listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
        }
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the table definitions need nothing beyond the base and its migrations`(dialect: DbDialect) {
        val database = database(dialect) ?: return

        val state = SchemaDrift.inspect(database)

        assertEquals(
            emptyList<String>(),
            state.pending,
            "The table definitions differ from the migrated schema. Run :server:generateMigration -Pname=<Name> " +
                "and commit the two files it writes."
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `every explicitly tolerated difference still exists`(dialect: DbDialect) {
        val database = database(dialect) ?: return
        val explicit = SchemaDrift.explicitDifferences[database.dialect].orEmpty().map { it.statement }

        val state = SchemaDrift.inspect(database)

        assertEquals(explicit.sorted(), state.required.filter { it in explicit }.sorted())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `only differences Exposed proposes are tolerated and each has one cause`(dialect: DbDialect) {
        val database = database(dialect) ?: return

        val state = SchemaDrift.inspect(database)

        assertEquals(state.required.sorted(), state.tolerated.map { it.statement }.sorted())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a table without a migration is drift`(dialect: DbDialect) {
        val database = database(dialect) ?: return

        val state = SchemaDrift.inspect(database, additionalTables = listOf(UnmigratedTable))

        assertEquals(statements(database) { UnmigratedTable.ddl }, state.pending)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a column and an index without a migration are drift`(dialect: DbDialect) {
        val database = database(dialect) ?: return

        val state = SchemaDrift.inspect(database, additionalTables = listOf(CustomMigrationWithUnmigratedColumn))

        val expected = statements(database) {
            CustomMigrationWithUnmigratedColumn.unmigrated.ddl +
                CustomMigrationWithUnmigratedColumn.indices.flatMap { it.createStatement() }
        }
        assertEquals(expected.sorted(), state.pending.sorted())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an ordinary index no table declares is drift`(dialect: DbDialect) {
        val database = database(dialect) ?: return
        val undeclared = Index(listOf(CustomMigrationTable.executedAt), unique = false, customName = "drift_probe_idx")
        SchemaDrift.manager(database).use { manager ->
            manager.init()
            manager.tempConnection { undeclared.createStatement().forEach { exec(it) } }
        }

        val state = SchemaDrift.inspect(database)

        assertEquals(statements(database) { undeclared.dropStatement() }, state.pending)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a second index over the columns of a declared one is drift`(dialect: DbDialect) {
        val database = database(dialect) ?: return
        val declared = HueBridgeTable.indices.single()
        val copy = Index(declared.columns, unique = declared.unique, customName = "drift_probe_copy")
        SchemaDrift.manager(database).use { manager ->
            manager.init()
            manager.tempConnection { copy.createStatement().forEach { exec(it) } }
        }

        val state = SchemaDrift.inspect(database)

        assertEquals(statements(database) { copy.dropStatement() }, state.pending)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the migrated schema raises no data risk warning`(dialect: DbDialect) {
        val database = database(dialect) ?: return

        val state = SchemaDrift.inspect(database, additionalTables = listOf(CustomMigrationWithUnmigratedColumn))

        assertEquals(emptyList<DataRisk>(), state.warnings.map { it.risk })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a column that replaces another one is reported as a possible rename`(dialect: DbDialect) {
        val database = database(dialect) ?: return

        val state = SchemaDrift.inspect(database, additionalTables = listOf(CustomMigrationWithRenamedColumn))

        val warning = state.warnings.single()
        assertEquals(CustomMigrationWithRenamedColumn, warning.table)
        assertEquals(DataRisk.POSSIBLE_RENAME, warning.risk)
        assertEquals(listOf(CustomMigrationWithRenamedColumn.finishedAt.name), warning.added)
        assertEquals(listOf(CustomMigrationTable.executedAt.name), warning.removed)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a new required column without a database default is reported`(dialect: DbDialect) {
        val database = database(dialect) ?: return

        val state = SchemaDrift.inspect(database, additionalTables = listOf(CustomMigrationWithRequiredColumn))

        val warning = state.warnings.single()
        assertEquals(CustomMigrationWithRequiredColumn, warning.table)
        assertEquals(DataRisk.REQUIRED_WITHOUT_DEFAULT, warning.risk)
        assertEquals(listOf(CustomMigrationWithRequiredColumn.required.name), warning.added)
    }

    private fun statements(database: MigrationDatabase, block: JdbcTransaction.() -> List<String>): List<String> =
        SchemaDrift.manager(database).use { it.tempConnection(block) }

    private fun database(dialect: DbDialect): MigrationDatabase? = when (dialect) {
        DbDialect.POSTGRES -> TestDatabase.postgresContainer?.let { container ->
            MigrationDatabase(
                dialect = Dialect.POSTGRES,
                driver = "org.postgresql.Driver",
                url = TestDatabase.getPostgresDbUrl(
                    "schema_drift_${UUID.randomUUID().toString().replace("-", "")}".lowercase()
                ),
                user = container.username,
                password = container.password,
            )
        } ?: run {
            println("Skipping PostgreSQL schema drift test because Docker is not available.")
            null
        }

        DbDialect.SQLITE -> {
            val file = File.createTempFile("schema_drift", ".db")
            files += file
            MigrationDatabase(Dialect.SQLITE, "org.sqlite.JDBC", "jdbc:sqlite:${file.absolutePath}", "", "")
        }
    }
}
