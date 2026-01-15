package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.core.*
import dev.dertyp.data.Artist
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.MergeArtists
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.db.AlbumArtistTable
import dev.dertyp.db.ArtistAliasTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import java.util.*

class ArtistService : IArtistService, Service() {
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

    override suspend fun byId(id: UUID): Artist? = querySingle {
        where { ArtistTable.id eq id }
    }

    override suspend fun rankedSearch(page: Int, pageSize: Int, query: String): PaginatedResponse<Artist> =
        queryArtists(page, pageSize) {
            rankedSearchQuery(
                query,
                listOf(10, 8),
                listOf(ArtistTable.name, ArtistAliasTable.name)
            )
        }

    override suspend fun byGroup(page: Int, pageSize: Int, groupId: UUID): PaginatedResponse<Artist> =
        queryArtists(page, pageSize) {
            where { ArtistTable.groupId eq groupId }
        }

    override suspend fun mergeArtists(mergeArtists: MergeArtists): Artist? = dbQuery {
        val currentArtists = ArtistTable
            .select(ArtistTable.id, ArtistTable.name)
            .where { ArtistTable.id inList mergeArtists.artistIds }
            .map { Pair(it[ArtistTable.id].value, it[ArtistTable.name]) }

        if (currentArtists.isEmpty()) {
            logger.info("No artist matched to $mergeArtists")
            return@dbQuery null
        }

        val image = mergeArtists.image?.let {
            when {
                it.toUUIDOrNull() != null -> {
                    val imageService by inject<ImageService>()
                    imageService.byId(it.toUUIDOrNull()!!)?.id
                }

                it.isURL() -> {
                    val imageService by inject<ImageService>()

                    val imageData = ApiClient.instance.safeGet<ByteArray>(it) ?: return@let null
                    imageService.createBatch(
                        listOf(
                            InsertableImage(
                                data = imageData,
                                imageHash = imageData.sha256(),
                                origin = it
                            )
                        )
                    ).firstOrNull()
                }

                else -> null
            }
        }

        val currentArtistIds = currentArtists.map { it.first }

        val newArtist = ArtistTable.insertAndGetId {
            it[ArtistTable.name] = mergeArtists.name
            it[ArtistTable.image] = image
        }.value

        val existingAlias = ArtistAliasTable
            .select(ArtistAliasTable.name, ArtistAliasTable.artistId)
            .where { ArtistAliasTable.artistId inList currentArtistIds }
            .map { it[ArtistAliasTable.name] }
            .distinct()

        val alias = currentArtists.flatMap { (_, artistName) ->
            listOf(artistName, artistName.stripAccents())
        } + existingAlias

        ArtistAliasTable.batchInsert(alias.distinct() - mergeArtists.name) {
            this[ArtistAliasTable.artistId] = newArtist
            this[ArtistAliasTable.name] = it
        }

        val songIds = SongArtistTable
            .select(SongArtistTable.songId, SongArtistTable.artistId)
            .where { SongArtistTable.artistId inList currentArtistIds }
            .map { it[SongArtistTable.songId].value }
            .distinct()

        SongArtistTable.batchInsert(songIds) { songId ->
            this[SongArtistTable.songId] = songId
            this[SongArtistTable.artistId] = newArtist
        }

        val albumIds = AlbumArtistTable
            .select(AlbumArtistTable.albumId, AlbumArtistTable.artistId)
            .where { AlbumArtistTable.artistId inList currentArtistIds }
            .map { it[AlbumArtistTable.albumId].value }
            .distinct()

        AlbumArtistTable.batchInsert(albumIds) { albumId ->
            this[AlbumArtistTable.albumId] = albumId
            this[AlbumArtistTable.artistId] = newArtist
        }

        SongArtistTable.deleteWhere { SongArtistTable.artistId inList currentArtistIds }
        AlbumArtistTable.deleteWhere { AlbumArtistTable.artistId inList currentArtistIds }

        ArtistTable.deleteWhere { ArtistTable.id inList currentArtistIds }
        ArtistAliasTable.deleteWhere { ArtistAliasTable.artistId inList currentArtistIds }

        logger.info("Merged artists $mergeArtists into $newArtist")

        return@dbQuery byId(newArtist)
    }

    override suspend fun allArtists(page: Int, pageSize: Int): PaginatedResponse<Artist> = queryArtists(page, pageSize)

    private suspend fun querySingle(query: Query.() -> Query) =
        queryArtists(0, Int.MAX_VALUE, query).data.singleOrNull()

    private suspend fun queryArtists(page: Int, pageSize: Int, query: Query.() -> Query = { this }) = dbQuery {
        val offset = if (pageSize == Int.MAX_VALUE) 0 else 1
        val mainArtistRows = ArtistTable
            .leftJoin(ArtistAliasTable)
            .selectAll()
            .query()
            .toList()

        val groupIds = mainArtistRows
            .filter { it[ArtistTable.isGroup] }
            .map { it[ArtistTable.id].value }
            .distinct()

        if (groupIds.isEmpty()) {
            return@dbQuery mainArtistRows
                .map { map(it) }
                .distinctBy { it.id }
                .let {
                    PaginatedResponse(
                        data = it.drop(page * pageSize).take(pageSize),
                        page = page,
                        pageSize = pageSize,
                        hasNextPage = it.drop(page * pageSize).size >= pageSize + offset,
                    )
                }
        }

        val memberDataRows = ArtistTable
            .selectAll()
            .where { ArtistTable.groupId inList groupIds }
            .toList()

        val data = mapEagerly(mainArtistRows, memberDataRows).distinctBy { it.id }

        PaginatedResponse(
            data = data.drop(page * pageSize).take(pageSize),
            page = page,
            pageSize = pageSize,
            hasNextPage = data.drop(page * pageSize).size >= pageSize + offset,
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
                .leftJoin(ArtistAliasTable)
                .select(ArtistTable.id, ArtistTable.name, ArtistAliasTable.name)
                .where { ArtistTable.name inList artistNames }
                .orWhere { ArtistAliasTable.name inList artistNames }
                .toList()
        }

        val existingNames = existingRows.flatMap { listOf(it[ArtistTable.name], it[ArtistAliasTable.name]) }.toSet()
        val existingMap = existingRows.flatMap {
            listOf(
                Pair(it[ArtistTable.name], it[ArtistTable.id].value),
                Pair(it[ArtistAliasTable.name], it[ArtistTable.id].value)
            )
        }.distinct().toMap()

        val newNames = artistNames.filter { it !in existingNames }

        val newRows = if (newNames.isNotEmpty()) {
            dbQuery {
                ArtistTable.batchInsert(newNames) { name ->
                    this[ArtistTable.name] = name
                }.also { rows ->
                    val artists = rows.associate { row ->
                        row[ArtistTable.name].stripAccents() to row[ArtistTable.id].value
                    }.filter { (name) -> !newNames.contains(name) }

                    ArtistAliasTable.batchInsert(artists.entries) { (name, artistId) ->
                        this[ArtistAliasTable.name] = name
                        this[ArtistAliasTable.artistId] = artistId
                    }
                }
            }
        } else {
            emptyList()
        }

        val newMap = newRows.associate { it[ArtistTable.name] to it[ArtistTable.id].value }

        return existingMap + newMap
    }
}

