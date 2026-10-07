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
import java.util.IdentityHashMap
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
    private companion object {
        val additions = listOf(
            null,
            UnmigratedTable,
            CustomMigrationWithUnmigratedColumn,
            CustomMigrationWithRenamedColumn,
            CustomMigrationWithRequiredColumn,
        )
        val undeclaredIndex =
            Index(listOf(CustomMigrationTable.executedAt), unique = false, customName = "drift_probe_idx")
        val copiedIndex = HueBridgeTable.indices.single().let { declared ->
            Index(declared.columns, unique = declared.unique, customName = "drift_probe_copy")
        }
        val indicesOnlyInDatabase = listOf(undeclaredIndex, copiedIndex)
        val inspections = HashMap<DbDialect, IdentityHashMap<Any?, SchemaState>>()
    }

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
        val state = inspection(dialect)

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
        val database = database(dialect)
        val explicit = SchemaDrift.explicitDifferences[database.dialect].orEmpty().map { it.statement }

        val state = inspection(dialect)

        assertEquals(explicit.sorted(), state.required.filter { it in explicit }.sorted())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `only differences Exposed proposes are tolerated and each has one cause`(dialect: DbDialect) {
        val state = inspection(dialect)

        assertEquals(state.required.sorted(), state.tolerated.map { it.statement }.sorted())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a table without a migration is drift`(dialect: DbDialect) {
        val database = database(dialect)

        val state = inspection(dialect, UnmigratedTable)

        assertEquals(statements(database) { UnmigratedTable.ddl }, state.pending)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a column and an index without a migration are drift`(dialect: DbDialect) {
        val database = database(dialect)

        val state = inspection(dialect, CustomMigrationWithUnmigratedColumn)

        val expected = statements(database) {
            CustomMigrationWithUnmigratedColumn.unmigrated.ddl +
                CustomMigrationWithUnmigratedColumn.indices.flatMap { it.createStatement() }
        }
        assertEquals(expected.sorted(), state.pending.sorted())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an ordinary index no table declares is drift`(dialect: DbDialect) {
        val database = database(dialect)

        val state = inspection(dialect, undeclaredIndex)

        assertEquals(statements(database) { undeclaredIndex.dropStatement() }, state.pending)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a second index over the columns of a declared one is drift`(dialect: DbDialect) {
        val database = database(dialect)

        val state = inspection(dialect, copiedIndex)

        assertEquals(statements(database) { copiedIndex.dropStatement() }, state.pending)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the migrated schema raises no data risk warning`(dialect: DbDialect) {
        val state = inspection(dialect, CustomMigrationWithUnmigratedColumn)

        assertEquals(emptyList<DataRisk>(), state.warnings.map { it.risk })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a column that replaces another one is reported as a possible rename`(dialect: DbDialect) {
        val state = inspection(dialect, CustomMigrationWithRenamedColumn)

        val warning = state.warnings.single()
        assertEquals(CustomMigrationWithRenamedColumn, warning.table)
        assertEquals(DataRisk.POSSIBLE_RENAME, warning.risk)
        assertEquals(listOf(CustomMigrationWithRenamedColumn.finishedAt.name), warning.added)
        assertEquals(listOf(CustomMigrationTable.executedAt.name), warning.removed)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a new required column without a database default is reported`(dialect: DbDialect) {
        val state = inspection(dialect, CustomMigrationWithRequiredColumn)

        val warning = state.warnings.single()
        assertEquals(CustomMigrationWithRequiredColumn, warning.table)
        assertEquals(DataRisk.REQUIRED_WITHOUT_DEFAULT, warning.risk)
        assertEquals(listOf(CustomMigrationWithRequiredColumn.required.name), warning.added)
    }

    private fun inspection(dialect: DbDialect, addition: Any? = null): SchemaState =
        inspections.getOrPut(dialect) {
            val states = SchemaDrift.inspectEach(
                database(dialect),
                additions.map { listOfNotNull(it) },
                indicesOnlyInDatabase,
            )
            IdentityHashMap<Any?, SchemaState>().apply {
                (additions + indicesOnlyInDatabase).zip(states).forEach { (addition, state) -> put(addition, state) }
            }
        }.getValue(addition)

    private fun statements(database: MigrationDatabase, block: JdbcTransaction.() -> List<String>): List<String> =
        SchemaDrift.manager(database).use { it.tempConnection(block) }

    private fun database(dialect: DbDialect): MigrationDatabase = when (dialect) {
        DbDialect.POSTGRES -> {
            val container = TestDatabase.postgresContainer
            MigrationDatabase(
                dialect = Dialect.POSTGRES,
                driver = "org.postgresql.Driver",
                url = TestDatabase.getMigratedPostgresDbUrl(
                    "schema_drift_${UUID.randomUUID().toString().replace("-", "")}".lowercase()
                ),
                user = container.username,
                password = container.password,
            )
        }

        DbDialect.SQLITE -> {
            val file = File.createTempFile("schema_drift", ".db")
            files += file
            MigrationDatabase(Dialect.SQLITE, "org.sqlite.JDBC", "jdbc:sqlite:${file.absolutePath}", "", "")
        }
    }
}
