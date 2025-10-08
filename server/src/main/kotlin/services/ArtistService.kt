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


        suspend fun mapArtist(resultRow: ResultRow, mapGroup: Boolean = true): Artist {
            val id = resultRow[ArtistTable.id].value
            val name = resultRow[ArtistTable.name]
            val isGroup = resultRow[ArtistTable.isGroup]
            val about = resultRow[ArtistTable.about]
            val imageId = resultRow[ArtistTable.image]?.value

            val artists = if (isGroup && mapGroup) dbQuery {
                ArtistTable
                    .selectAll()
                    .where { ArtistTable.groupId eq id }
                    .map { mapArtist(it) }
            } else listOf()

            return Artist(
                id = id,
                name = name,
                isGroup = isGroup,
                artists = artists,
                about = about,
                imageId = imageId
            )
        }
    }

    suspend fun map(resultRow: ResultRow): Artist = mapArtist(resultRow)

    suspend fun byId(id: UUID): Artist? = dbQuery {
        ArtistTable
            .selectAll()
            .where { ArtistTable.id eq id }
            .map { map(it) }.singleOrNull()
    }

    suspend fun byName(name: String): List<Artist> = dbQuery {
        ArtistTable
            .selectAll()
            .where { ArtistTable.name eq name }
            .map { map(it) }
    }

    suspend fun searchByName(name: String): List<Artist> = dbQuery {
        ArtistTable
            .selectAll()
            .where { ArtistTable.name like "%$name%" }
            .map { map(it) }
    }

    suspend fun byGroup(groupId: UUID): List<Artist> = dbQuery {
        ArtistTable
            .selectAll()
            .where { ArtistTable.groupId eq groupId }
            .map { map(it) }
    }

    suspend fun allArtists(): List<Artist> = dbQuery {
        ArtistTable.selectAll().map { map(it) }
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

