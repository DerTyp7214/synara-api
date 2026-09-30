package dev.dertyp.db.migrations

import dev.dertyp.core.db.bind
import dev.dertyp.core.db.execute
import dev.dertyp.core.tempConnection
import dev.dertyp.db.FavSyncTable
import dev.dertyp.db.ListenSource
import dev.dertyp.db.ListenTable
import dev.dertyp.db.UserTable
import dev.dertyp.services.ISyncService
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import io.ktor.util.logging.KtorSimpleLogger
import java.sql.Connection

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_103__StoreEnumsByName : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        if (columnExists(connection, ListenTable, LEGACY_LISTEN_SOURCE)) migrateListen(connection)
        if (columnExists(connection, FavSyncTable, LEGACY_FAV_SYNC_SERVICE)) migrateFavSync(connection)
    }

    private fun migrateListen(connection: Connection) {
        val legacy = Column<Int>(ListenTable, LEGACY_LISTEN_SOURCE, IntegerColumnType())

        val (addColumns, backfill, dropLegacy) = tempConnection {
            Triple(
                SchemaUtils.addMissingColumnsStatements(ListenTable)
                    .map { it.replaceFirst("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ") },
                ListenSource.entries.map { source ->
                    UpdateStatement(ListenTable, null, legacy eq source.ordinal)
                        .apply { this[ListenTable.listenSource] = source }
                        .bind(this)
                },
                listOf("DROP INDEX IF EXISTS $LEGACY_LISTEN_INDEX") + legacy.dropStatement()
            )
        }

        connection.createStatement().use { statement ->
            for (sql in addColumns) statement.execute(sql)
        }
        backfill.forEach { connection.execute(it) }
        connection.createStatement().use { statement ->
            for (sql in dropLegacy) statement.execute(sql)
        }
    }

    private fun migrateFavSync(connection: Connection) {
        val legacy = Column<Int>(FavSyncTable, LEGACY_FAV_SYNC_SERVICE, IntegerColumnType())
        val services = ISyncService.SyncServiceType.entries

        val (select, countOrphans) = tempConnection {
            val userExists = exists(UserTable.select(UserTable.id).where { UserTable.id eq FavSyncTable.userId })
            FavSyncTable
                .select(FavSyncTable.userId, legacy, FavSyncTable.syncedAt)
                .where { userExists }
                .prepareSQL(this, prepared = false) to
                FavSyncTable.select(FavSyncTable.userId.count())
                    .where { not(userExists) }
                    .prepareSQL(this, prepared = false)
        }

        val orphans = connection.createStatement().use { statement ->
            statement.executeQuery(countOrphans).use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

        val rows = connection.createStatement().use { statement ->
            statement.executeQuery(select).use { rs ->
                buildList {
                    while (rs.next()) {
                        val service = services.getOrNull(rs.getInt(2)) ?: continue
                        add(LegacyFavSync(rs.getObject(1), service, rs.getLong(3)))
                    }
                }
            }
        }

        val (rebuild, inserts) = tempConnection {
            FavSyncTable.dropStatement() + FavSyncTable.createStatement() to rows.map { row ->
                InsertStatement<Number>(FavSyncTable).apply {
                    this[FavSyncTable.userId] = requireNotNull(FavSyncTable.userId.columnType.valueFromDB(row.userId))
                    this[FavSyncTable.service] = row.service
                    this[FavSyncTable.syncedAt] = row.syncedAt
                }.bind(this)
            }
        }

        if (orphans > 0) logger.warn("Dropped $orphans favSync row(s) whose user no longer exists")

        connection.createStatement().use { statement ->
            for (sql in rebuild) statement.execute(sql)
        }
        inserts.forEach { connection.execute(it) }
    }

    private fun columnExists(connection: Connection, table: Table, column: String): Boolean =
        listOf(table.tableName, table.tableName.lowercase()).distinct().any { name ->
            connection.metaData.getColumns(null, null, name, null).use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("COLUMN_NAME").equals(column, ignoreCase = true)) found = true
                }
                found
            }
        }

    private class LegacyFavSync(val userId: Any, val service: ISyncService.SyncServiceType, val syncedAt: Long)

    companion object {
        private const val LEGACY_LISTEN_SOURCE = "source"
        private const val LEGACY_LISTEN_INDEX = "listen_source_updatedAt"
        private const val LEGACY_FAV_SYNC_SERVICE = "service"
        private val logger = KtorSimpleLogger("V1_103__StoreEnumsByName")
    }
}
