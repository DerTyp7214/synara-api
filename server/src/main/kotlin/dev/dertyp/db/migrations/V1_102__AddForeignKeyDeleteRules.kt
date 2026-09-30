package dev.dertyp.db.migrations

import dev.dertyp.core.db.*
import dev.dertyp.core.tempConnection
import dev.dertyp.db.*
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.select

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_102__AddForeignKeyDeleteRules : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        if (Dialect.of(connection) != Dialect.POSTGRES) return

        val columns = listOf(
            SessionTable.userId,
            RefreshTokenTable.userId,
            UserPlaylistTable.creator,
            CollectionTable.creator,
            SongTable.cover,
            AlbumTable.cover,
            ArtistTable.image,
            PlaylistTable.imageId,
            UserPlaylistTable.imageId,
            CollectionTable.imageId,
            UserQueueTable.modifiedBySessionId,
        )
        val existingNames = columns.associateWith { existingForeignKeyNames(connection, it) }

        val (clearMissingSessions, statements) = tempConnection {
            val clearMissingSessions = UpdateStatement(
                UserQueueTable,
                null,
                UserQueueTable.modifiedBySessionId.isNotNull() and notExists(
                    SessionTable.select(SessionTable.id).where { SessionTable.id eq UserQueueTable.modifiedBySessionId }
                )
            ).apply {
                this[UserQueueTable.modifiedBySessionId] = null
            }.bind(this)

            clearMissingSessions to columns.flatMap { column ->
                val key = requireNotNull(column.foreignKey)
                existingNames.getValue(column).flatMap { key.copy(name = it).dropStatement() } + key.createStatement()
            }
        }

        connection.execute(clearMissingSessions)
        connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }
    }
}
