package dev.dertyp.services

import dev.dertyp.data.Artist
import dev.dertyp.db.ArtistTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class ArtistService(database: Database) {
    init {
        transaction(database) {
            SchemaUtils.create(ArtistTable)
        }

        instance = this
    }

    companion object {
        var instance: ArtistService? = null
            private set

        fun mapArtist(resultRow: ResultRow): Artist {
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

    fun map(resultRow: ResultRow): Artist = mapArtist(resultRow)

    suspend fun byId(id: UUID): Artist? = queryArtists {
        where { ArtistTable.id eq id }
    }.singleOrNull()

    suspend fun byName(name: String): List<Artist> = queryArtists {
        where { ArtistTable.name eq name }
    }

    suspend fun searchByName(name: String): List<Artist> = queryArtists {
        where { ArtistTable.name like "%$name%" }
    }

    suspend fun byGroup(groupId: UUID): List<Artist> = queryArtists {
        where { ArtistTable.groupId eq groupId }
    }

    suspend fun allArtists(): List<Artist> = queryArtists()

    private suspend fun queryArtists(query: Query.() -> Query = { this }) = dbQuery {
        val mainArtistRows = ArtistTable
            .selectAll()
            .query()
            .toList()

        val groupIds = mainArtistRows.filter { it[ArtistTable.isGroup] }
            .map { it[ArtistTable.id].value }

        if (groupIds.isEmpty()) {
            return@dbQuery mainArtistRows.map { map(it) }
        }

        val memberDataRows = ArtistTable
            .selectAll()
            .where { ArtistTable.groupId inList groupIds }
            .toList()

        mapEagerly(mainArtistRows, memberDataRows)
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

    suspend fun getOrCreate(artistName: String): UUID? {
        val artist = byName(artistName)
        if (artist.isNotEmpty()) return artist.singleOrNull()?.id

        return dbQuery {
            ArtistTable.insertAndGetId {
                it[name] = artistName
            }
        }.value
    }
}

