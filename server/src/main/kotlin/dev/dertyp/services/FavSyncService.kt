package dev.dertyp.services

import dev.dertyp.core.date
import dev.dertyp.data.FavSync
import dev.dertyp.data.User
import dev.dertyp.db.FavSyncTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.*

class FavSyncRpcService(private val user: User, private val favSyncService: FavSyncService) : IFavSyncService {
    override suspend fun getLatestFavSync(
        service: ISyncService.SyncServiceType
    ): FavSync? = favSyncService.getLatestFavSync(user, service)

    override suspend fun insertFavSync(
        service: ISyncService.SyncServiceType,
        syncedAt: Date
    ): Int = favSyncService.insertFavSync(user, service, syncedAt)
}

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

    suspend fun getLatestFavSync(user: User, service: ISyncService.SyncServiceType): FavSync? = queryFavSync {
        where { FavSyncTable.userId eq user.id }
        andWhere { FavSyncTable.service eq service }
        orderBy(FavSyncTable.syncedAt, SortOrder.DESC)
        limit(1)
    }.singleOrNull()

    suspend fun insertFavSync(user: User, service: ISyncService.SyncServiceType, syncedAt: Date): Int = dbQuery {
        FavSyncTable.upsert {
            it[FavSyncTable.userId] = user.id
            it[FavSyncTable.service] = service
            it[FavSyncTable.syncedAt] = syncedAt.toInstant().toEpochMilli()
        }.insertedCount
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