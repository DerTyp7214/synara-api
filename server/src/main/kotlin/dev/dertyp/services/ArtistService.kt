package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.core.*
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import java.util.UUID

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

    override suspend fun byIds(ids: List<UUID>): List<Artist> = queryArtists(0, Int.MAX_VALUE) {
        where { ArtistTable.id inList ids }
    }.data

    override suspend fun rankedSearch(page: Int, pageSize: Int, query: String): PaginatedResponse<Artist> =
        queryArtists(page, pageSize) {
            rankedSearchQuery(
                query,
                listOf(10, 8),
                listOf(ArtistTable.name, ArtistAliasTable.name),
                ArtistTable.id
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

    override suspend fun splitArtist(splitArtist: SplitArtist): List<Artist> {
        val originalArtistId = splitArtist.artistId

        val originalArtistData = dbQuery {
            val mainRow = ArtistTable
                .select(ArtistTable.name)
                .where { ArtistTable.id eq originalArtistId }
                .singleOrNull() ?: return@dbQuery null

            val aliases = ArtistAliasTable
                .select(ArtistAliasTable.name)
                .where { ArtistAliasTable.artistId eq originalArtistId }
                .map { it[ArtistAliasTable.name] }

            listOf(mainRow[ArtistTable.name]) + aliases
        } ?: return emptyList()

        val targetArtistIds = mutableListOf<UUID>()
        val namesToResolve = mutableListOf<String>()

        splitArtist.newArtists.forEach { (name, id) ->
            if (id != null) {
                targetArtistIds.add(id)
            } else {
                namesToResolve.add(name)
            }
        }

        if (namesToResolve.isNotEmpty()) {
            targetArtistIds.addAll(getOrBulkCreate(namesToResolve).values.flatten())
        }

        val finalTargetIds = targetArtistIds.distinct()

        dbQuery {
            val songIds = SongArtistTable
                .select(SongArtistTable.songId)
                .where { SongArtistTable.artistId eq originalArtistId }
                .map { it[SongArtistTable.songId].value }

            val albumIds = AlbumArtistTable
                .select(AlbumArtistTable.albumId)
                .where { AlbumArtistTable.artistId eq originalArtistId }
                .map { it[AlbumArtistTable.albumId].value }

            finalTargetIds.forEach { targetId ->
                if (targetId == originalArtistId) return@forEach

                val existingSongIds = SongArtistTable
                    .select(SongArtistTable.songId)
                    .where { (SongArtistTable.artistId eq targetId) and (SongArtistTable.songId inList songIds) }
                    .map { it[SongArtistTable.songId].value }
                    .toSet()

                val songsToInsert = songIds.filter { it !in existingSongIds }
                if (songsToInsert.isNotEmpty()) {
                    SongArtistTable.batchInsert(songsToInsert) { songId ->
                        this[SongArtistTable.songId] = songId
                        this[SongArtistTable.artistId] = targetId
                    }
                }

                val existingAlbumIds = AlbumArtistTable
                    .select(AlbumArtistTable.albumId)
                    .where { (AlbumArtistTable.artistId eq targetId) and (AlbumArtistTable.albumId inList albumIds) }
                    .map { it[AlbumArtistTable.albumId].value }
                    .toSet()

                val albumsToInsert = albumIds.filter { it !in existingAlbumIds }
                if (albumsToInsert.isNotEmpty()) {
                    AlbumArtistTable.batchInsert(albumsToInsert) { albumId ->
                        this[AlbumArtistTable.albumId] = albumId
                        this[AlbumArtistTable.artistId] = targetId
                    }
                }

                // Register split aliases
                originalArtistData.forEach { name ->
                    ArtistSplitAliasTable.insertIgnore {
                        it[ArtistSplitAliasTable.name] = name
                        it[ArtistSplitAliasTable.artistId] = targetId
                    }
                }
            }

            if (originalArtistId !in finalTargetIds) {
                SongArtistTable.deleteWhere { SongArtistTable.artistId eq originalArtistId }
                AlbumArtistTable.deleteWhere { AlbumArtistTable.artistId eq originalArtistId }
                ArtistTable.deleteWhere { ArtistTable.id eq originalArtistId }
                ArtistAliasTable.deleteWhere { ArtistAliasTable.artistId eq originalArtistId }
            }

            logger.info("Split artist $originalArtistId into $finalTargetIds")
        }

        return byIds(finalTargetIds)
    }

    override suspend fun allArtists(page: Int, pageSize: Int): PaginatedResponse<Artist> = queryArtists(page, pageSize)

    fun allArtistIds(): Flow<UUID> = flow {
        ArtistTable
            .select(ArtistTable.id)
            .fetchBatchedResults(1000) { batch ->
                batch.forEach { emit(it[ArtistTable.id].value) }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun allArtistsFlow(): Flow<Artist> = allArtistIds().chunked(100).flatMapConcat { ids ->
        byIds(ids).asFlow()
    }

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
                        total = it.size,
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
            total = data.size,
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

    suspend fun getOrBulkCreate(artistNames: List<String>): Map<String, List<UUID>> {
        val existingSplits = dbQuery {
            ArtistSplitAliasTable
                .select(ArtistSplitAliasTable.name, ArtistSplitAliasTable.artistId)
                .where { ArtistSplitAliasTable.name inList artistNames }
                .toList()
                .groupBy({ it[ArtistSplitAliasTable.name] }, { it[ArtistSplitAliasTable.artistId].value })
        }

        val namesToResolve = artistNames.filter { it !in existingSplits.keys }

        val existingRows = dbQuery {
            ArtistTable
                .leftJoin(ArtistAliasTable)
                .select(ArtistTable.id, ArtistTable.name, ArtistAliasTable.name)
                .where { ArtistTable.name inList namesToResolve }
                .orWhere { ArtistAliasTable.name inList namesToResolve }
                .toList()
        }

        val existingNames = existingRows.flatMap { listOfNotNull(it[ArtistTable.name], it.getOrNull(ArtistAliasTable.name)) }.toSet()
        val existingMap = existingRows.flatMap {
            val mainName = it[ArtistTable.name]
            val aliasName = it.getOrNull(ArtistAliasTable.name)
            val artistId = it[ArtistTable.id].value
            listOfNotNull(
                mainName to artistId,
                aliasName?.let { name -> name to artistId }
            )
        }.distinct().groupBy({ it.first }, { it.second })

        val newNames = namesToResolve.filter { it !in existingNames }

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

        val newMap = newRows.associate { it[ArtistTable.name] to listOf(it[ArtistTable.id].value) }

        return existingSplits + existingMap + newMap
    }

    suspend fun deleteUnreferencedArtists() = dbQuery {
        val referencedArtists = mutableSetOf<UUID>()
        referencedArtists.addAll(SongArtistTable.select(SongArtistTable.artistId).map { it[SongArtistTable.artistId].value })
        referencedArtists.addAll(AlbumArtistTable.select(AlbumArtistTable.artistId).map { it[AlbumArtistTable.artistId].value })
        referencedArtists.addAll(ArtistTable.select(ArtistTable.groupId).mapNotNull { it[ArtistTable.groupId]?.value })

        val allArtists = ArtistTable.select(ArtistTable.id).map { it[ArtistTable.id].value }

        val unreferencedArtists = allArtists.filter { it !in referencedArtists }

        unreferencedArtists.chunked(5000).forEach { batch ->
            ArtistTable.deleteWhere { ArtistTable.id inList batch }
            ArtistAliasTable.deleteWhere { ArtistAliasTable.artistId inList batch }
        }
        logger.info("Deleted ${unreferencedArtists.size} unreferenced artists")
    }

    suspend fun upsertArtist(artist: Artist) = dbQuery {
        ArtistTable.upsert(ArtistTable.id) {
            it[id] = artist.id
            it[name] = artist.name
            it[isGroup] = artist.isGroup
            it[about] = artist.about
            it[image] = artist.imageId?.let { imageId -> EntityID(imageId, ImageTable) }
        }
    }

    suspend fun upsertArtistAlias(alias: ArtistAlias) = dbQuery {
        ArtistAliasTable.upsert(ArtistAliasTable.artistId, ArtistAliasTable.name) {
            it[artistId] = alias.artistId
            it[name] = alias.name
        }
    }

    suspend fun upsertArtistSplitAlias(alias: ArtistSplitAlias) = dbQuery {
        ArtistSplitAliasTable.upsert(ArtistSplitAliasTable.artistId, ArtistSplitAliasTable.name) {
            it[artistId] = alias.artistId
            it[name] = alias.name
        }
    }
}
