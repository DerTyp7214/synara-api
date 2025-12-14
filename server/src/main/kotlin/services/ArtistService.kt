package dev.dertyp.services

import dev.dertyp.core.rankedSearchQuery
import dev.dertyp.data.Artist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.db.ArtistTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.sql.*
import java.util.*

class ArtistService: Service() {
    companion object {
        fun mapArtist(resultRow: ResultRow, table: ColumnSet = ArtistTable): Artist {
            if (table is Alias<*>) {
                return Artist(
                    id = resultRow[table[ArtistTable.id]].value,
                    name = resultRow[table[ArtistTable.name]],
                    isGroup = resultRow[table[ArtistTable.isGroup]],
                    artists = listOf(),
                    about = resultRow[table[ArtistTable.about]],
                    imageId = resultRow[table[ArtistTable.image]]?.value,
                )
            } else {
                return Artist(
                    id = resultRow[ArtistTable.id].value,
                    name = resultRow[ArtistTable.name],
                    isGroup = resultRow[ArtistTable.isGroup],
                    artists = listOf(),
                    about = resultRow[ArtistTable.about],
                    imageId = resultRow[ArtistTable.image]?.value,
                )
            }
        }
    }

    fun map(resultRow: ResultRow): Artist = mapArtist(resultRow)

    suspend fun byId(id: UUID): Artist? = querySingle {
        where { ArtistTable.id eq id }
    }

    suspend fun rankedSearch(page: Int, pageSize: Int, query: String): PaginatedResponse<Artist> =
        queryArtists(page, pageSize) {
            rankedSearchQuery(
                query,
                listOf(10),
                listOf(ArtistTable.name)
            )
        }

    suspend fun byGroup(page: Int, pageSize: Int, groupId: UUID): PaginatedResponse<Artist> =
        queryArtists(page, pageSize) {
            where { ArtistTable.groupId eq groupId }
        }

    suspend fun allArtists(page: Int, pageSize: Int): PaginatedResponse<Artist> = queryArtists(page, pageSize)

    private suspend fun querySingle(query: Query.() -> Query) =
        queryArtists(0, Int.MAX_VALUE, query).data.singleOrNull()

    private suspend fun queryArtists(page: Int, pageSize: Int, query: Query.() -> Query = { this }) = dbQuery {
        val offset = if (pageSize == Int.MAX_VALUE) 0 else 1
        val mainArtistRows = ArtistTable
            .selectAll()
            .query()
            .toList()

        val groupIds = mainArtistRows.filter { it[ArtistTable.isGroup] }
            .map { it[ArtistTable.id].value }

        if (groupIds.isEmpty()) {
            return@dbQuery mainArtistRows.map { map(it) }.let {
                PaginatedResponse(
                    data = it.take(pageSize),
                    page = page,
                    pageSize = pageSize,
                    hasNextPage = it.size == pageSize + offset,
                )
            }
        }

        val memberDataRows = ArtistTable
            .selectAll()
            .where { ArtistTable.groupId inList groupIds }
            .toList()

        val data = mapEagerly(mainArtistRows, memberDataRows)

        PaginatedResponse(
            data = data.take(pageSize),
            page = page,
            pageSize = pageSize,
            hasNextPage = data.size == pageSize + offset,
        )
    }

    private fun mapEagerly(mainRows: List<ResultRow>, memberRows: List<ResultRow>): List<Artist> {
        val membersByGroupId = memberRows
            .mapNotNull { row ->
                val groupId = row[ArtistTable.groupId]?.value ?: return@mapNotNull null
                val artist = mapArtist(row)
                groupId to artist
            }
            .groupBy({ it.first }, { it.second })

        return mainRows.map { mainRow ->
            val artist = map(mainRow)

            return@map if (artist.isGroup) {
                val memberArtists = membersByGroupId[artist.id] ?: listOf()
                artist.copy(artists = memberArtists)
            } else {
                artist
            }
        }
    }

    suspend fun getOrBulkCreate(artistNames: List<String>): Map<String, UUID> {
        val existingRows = dbQuery {
            ArtistTable
                .select(ArtistTable.id, ArtistTable.name)
                .where { ArtistTable.name inList artistNames }
                .toList()
        }

        val existingNames = existingRows.map { it[ArtistTable.name] }.toSet()
        val existingMap = existingRows.associate { it[ArtistTable.name] to it[ArtistTable.id].value }

        val newNames = artistNames.filter { it !in existingNames }

        val newRows = if (newNames.isNotEmpty()) {
            dbQuery {
                ArtistTable.batchInsert(newNames) { name ->
                    this[ArtistTable.name] = name
                }
            }
        } else {
            emptyList()
        }

        val newMap = newRows.associate { it[ArtistTable.name] to it[ArtistTable.id].value }

        return existingMap + newMap
    }
}

