package dev.dertyp.services

import dev.dertyp.core.date
import dev.dertyp.data.FavSync
import dev.dertyp.data.User
import dev.dertyp.db.FavSyncTable
import dev.dertyp.dbQuery
import dev.dertyp.services.sync.SyncService
import org.jetbrains.exposed.sql.*
import java.util.*

class FavSyncService : Service() {
    companion object {
        fun mapFavSync(resultRow: ResultRow): FavSync {
            return FavSync(
                userId = resultRow[FavSyncTable.userId].value,
                service = resultRow[FavSyncTable.service],
                syncedAt = resultRow[FavSyncTable.syncedAt].date
            )
        }
    }

    fun map(resultRow: ResultRow) = mapFavSync(resultRow)

    suspend fun getLatestFavSync(user: User, service: SyncService.SyncServiceType) = queryFavSync {
        where { FavSyncTable.userId eq user.id }
        andWhere { FavSyncTable.service eq service }
        orderBy(FavSyncTable.syncedAt, SortOrder.DESC)
        limit(1)
    }.singleOrNull()

    suspend fun insertFavSync(user: User, service: SyncService.SyncServiceType, syncedAt: Date) = dbQuery {
        FavSyncTable.upsert {
            it[FavSyncTable.userId] = user.id
            it[FavSyncTable.service] = service
            it[FavSyncTable.syncedAt] = syncedAt.toInstant().toEpochMilli()
        }
    }

    private suspend fun queryFavSync(
        columnSet: suspend ColumnSet.() -> ColumnSet = { this },
        query: suspend Query.() -> Query = { this },
    ) = dbQuery {
        val result = FavSyncTable
            .columnSet()
            .selectAll()
            .query()
            .map { map(it) }

        return@dbQuery result
    }
}