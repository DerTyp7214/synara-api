package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.PlatformUUID
import dev.dertyp.core.*
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.plugins.ArtistLibrary
import dev.dertyp.services.metadata.*
import dev.dertyp.utils.ColorUtils
import dev.dertyp.utils.LogParam
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class ArtistRpcService(private val user: User, private val artistService: ArtistService) : IArtistService {
    override suspend fun byId(id: UUID): Artist? = artistService.byId(id, user.id)
    override suspend fun byMusicBrainzId(mbId: UUID): List<Artist> = artistService.byMusicBrainzId(mbId, user.id)
    override suspend fun byIds(ids: List<UUID>): List<Artist> = artistService.byIds(ids, user.id)
    override suspend fun rankedSearch(page: Int, pageSize: Int, query: String): PaginatedResponse<Artist> =
        artistService.rankedSearch(page, pageSize, query, user.id)

    override suspend fun setGroup(id: UUID, artistIds: List<UUID>?): Artist? = artistService.setGroup(id, artistIds, user.id)
    override suspend fun byGroup(page: Int, pageSize: Int, groupId: UUID): PaginatedResponse<Artist> =
        artistService.byGroup(page, pageSize, groupId, user.id)

    override suspend fun mergeArtists(mergeArtists: MergeArtists): Artist? = artistService.mergeArtists(mergeArtists, user.id)
    override suspend fun splitArtist(splitArtist: SplitArtist): List<Artist> = artistService.splitArtist(splitArtist, user.id)
    override suspend fun allArtists(page: Int, pageSize: Int): PaginatedResponse<Artist> =
        artistService.allArtists(page, pageSize, user.id)

    override suspend fun byColor(
        page: Int,
        pageSize: Int,
        color: Int,
        range: Int
    ): PaginatedResponse<Artist> = artistService.byColor(page, pageSize, color, range, user.id)

    override suspend fun createArtist(
        name: String,
        isGroup: Boolean,
        about: String,
        musicBrainzId: UUID?
    ): Artist = artistService.createArtist(name, isGroup, about, musicBrainzId, user.id)

    override suspend fun searchArtistOnMusicBrainz(
        query: String,
        page: Int,
        pageSize: Int
    ): PaginatedResponse<MusicBrainzArtist> = artistService.searchArtistOnMusicBrainz(query, page, pageSize, HttpClientPriority.HIGH)

    override suspend fun fetchMusicBrainzId(id: UUID): Artist? = artistService.fetchMusicBrainzId(id, user.id, HttpClientPriority.HIGH)
    override suspend fun setMusicBrainzId(id: UUID, musicBrainzId: UUID?): Artist? =
        artistService.setMusicBrainzId(id, musicBrainzId, user.id)

    override suspend fun searchArtistImages(
        type: IMetadataService.MetadataType,
        query: String,
        limit: Int
    ): List<IMetadataService.Image> = artistService.searchArtistImages(type, query, limit)

    override suspend fun setArtistImageByUrl(id: UUID, url: String): Artist? =
        artistService.setArtistImageByUrl(id, url, user.id)

    override fun artistsWithoutMusicBrainzIdFlow(): Flow<Artist> = artistService.artistsWithoutMusicBrainzIdFlow(user.id)
    override fun artistIdsWithoutMusicBrainzId(): Flow<UUID> = artistService.artistIdsWithoutMusicBrainzId()

    override suspend fun aliases(id: UUID): List<ArtistAlias> = artistService.aliases(id)
    override suspend fun addAlias(artistId: UUID, name: String): Boolean = artistService.addAlias(artistId, name)
    override suspend fun removeAlias(artistId: UUID, name: String): Boolean = artistService.removeAlias(artistId, name)
}

class ArtistService(private val searchIndexWorker: SearchIndexWorker? = null) : ArtistLibrary, Service() {
    private val environment by inject<ApplicationEnvironment>()
    private val musicBrainzService by inject<MusicBrainzService>()
    private val cachedMusicBrainzService by inject<CachedMusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()
    private val metadataFetchingService by inject<MetadataFetchingService>()
    private val songService by inject<SongService>()
    private val albumService by inject<AlbumService>()
    private val genreService by inject<GenreService>()
    val artistGroupAlias = ArtistTable.alias("artistGroup")
    val artistMemberAlias = ArtistTable.alias("artistMember")
    val artistGroupJoinAlias = ArtistMemberTable.alias("artistGroupJoin")
    val artistMemberJoinAlias = ArtistMemberTable.alias("artistMemberJoin")
    val followedArtistAlias = FollowedArtistTable.alias("followedArtist")

    companion object {
        fun mapArtist(
            resultRow: ResultRow,
            table: ColumnSet = ArtistTable,
            musicbrainzId: UUID? = null,
            genres: List<Genre> = listOf(),
            followedTable: ColumnSet = FollowedArtistTable,
            blurHashColumn: Expression<String?>? = null
        ): Artist {
            val id: UUID
            val name: String
            val isGroup: Boolean
            val about: String
            val imageId: UUID?
            val blurHash: String?

            if (table is Alias<*>) {
                id = resultRow[table[ArtistTable.id]].value
                name = resultRow[table[ArtistTable.name]]
                isGroup = resultRow[table[ArtistTable.isGroup]]
                about = resultRow[table[ArtistTable.about]]
                imageId = resultRow[table[ArtistTable.image]]?.value
                blurHash = resultRow.getOrNull(blurHashColumn ?: ImageTable.blurHash)
            } else {
                id = resultRow[ArtistTable.id].value
                name = resultRow[ArtistTable.name]
                isGroup = resultRow[ArtistTable.isGroup]
                about = resultRow[ArtistTable.about]
                imageId = resultRow[ArtistTable.image]?.value
                blurHash = resultRow.getOrNull(blurHashColumn ?: ImageTable.blurHash)
            }

            val isFollowed = if (followedTable is Alias<*>) {
                resultRow.getOrNull(followedTable[FollowedArtistTable.userId]) != null
            } else {
                resultRow.getOrNull(FollowedArtistTable.userId) != null
            }

            return Artist(
                id = id,
                name = name,
                isGroup = isGroup,
                artists = listOf(),
                about = about,
                genres = genres,
                imageId = imageId,
                blurHash = blurHash,
                musicbrainzId = musicbrainzId ?: if (table == ArtistTable) resultRow.getOrNull(
                    ArtistMusicBrainzTable.musicBrainzId
                )?.value else null,
                isFollowed = isFollowed
            )
        }
    }

    fun map(resultRow: ResultRow): Artist = mapArtist(resultRow)

    private fun ColumnSet.followedArtist(userId: UUID?) = if (userId != null) {
        leftJoin(
            followedArtistAlias,
            onColumn = { ArtistTable.id },
            otherColumn = { followedArtistAlias[FollowedArtistTable.artistId] },
            additionalConstraint = { followedArtistAlias[FollowedArtistTable.userId] eq userId }
        )
    } else this

    suspend fun fetchMusicBrainzId(id: UUID, userId: UUID? = null, priority: HttpClientPriority = HttpClientPriority.NORMAL): Artist? {
        val artist = byId(id, userId) ?: return null
        
        var mbArtistId: UUID? = artist.musicbrainzId

        if (mbArtistId == null) {
            val songIds = songService.songIdsByArtist(id).take(5).toList()
            for (songId in songIds) {
                val song = songService.byId(songId) ?: continue
                val mbRecording = if (song.musicBrainzId != null) {
                    cachedMusicBrainzService.getRecording(song.musicBrainzId!!, priority)
                } else {
                    musicBrainzService.searchMb(song, priority)
                }

                if (mbRecording != null) {
                    if (song.musicBrainzId == null) {
                        musicBrainzCacheService.updateRecordingCache(mbRecording)
                    }

                    val matchedArtist = mbRecording.artistCredit?.find {
                        it.name.equals(artist.name, ignoreCase = true) || it.artist?.name.equals(artist.name, ignoreCase = true)
                    }?.artist

                    if (matchedArtist != null) {
                        mbArtistId = matchedArtist.id
                        break
                    }
                }
            }
        }

        if (mbArtistId == null) {
            val albums = albumService.byArtist(page = 0, pageSize = 5, artistId = id, singles = false, userId = userId).data
            for (album in albums) {
                val mbRelease = if (album.musicbrainzId != null) {
                    cachedMusicBrainzService.getRelease(album.musicbrainzId!!, priority)
                } else {
                    musicBrainzService.searchAlbumMb(album, priority)?.also {
                        musicBrainzCacheService.updateReleaseCache(it)
                    }
                }
                
                if (mbRelease != null) {
                    val matchedArtist = mbRelease.artistCredit?.find {
                        it.name.equals(artist.name, ignoreCase = true) || it.artist?.name.equals(artist.name, ignoreCase = true)
                    }?.artist
                    
                    if (matchedArtist != null) {
                        mbArtistId = matchedArtist.id
                        break
                    }
                }
            }
        }
        
        val mbArtist = if (mbArtistId != null) {
            cachedMusicBrainzService.getArtist(mbArtistId, priority)
        } else return byId(id, userId)
        
        if (mbArtist != null) {
            val genres = mbArtist.genres?.map { it.name } ?: emptyList()
            if (genres.isNotEmpty()) {
                val genreIds = genreService.getOrCreateGenres(genres)
                dbQuery {
                    ArtistGenreTable.deleteWhere { ArtistGenreTable.artistId eq id }
                    ArtistGenreTable.batchInsert(genreIds) { genreId ->
                        this[ArtistGenreTable.artistId] = id
                        this[ArtistGenreTable.genreId] = genreId
                    }
                }
            }
        }
        
        return setMusicBrainzId(id, mbArtist?.id, userId)
    }

    suspend fun updateMusicBrainzLastCheck(id: UUID) = dbQuery {
        val exists = ArtistTable.select(ArtistTable.id).where { ArtistTable.id eq id }.any()
        if (!exists) return@dbQuery

        ArtistMusicBrainzTable.upsert(ArtistMusicBrainzTable.artistId) {
            it[artistId] = id
            it[lastCheck] = Clock.System.now().toEpochMilliseconds()
        }
    }

    suspend fun setMusicBrainzId(id: UUID, musicBrainzId: UUID?, userId: UUID? = null): Artist? {
        val oldMbId = dbQuery {
            ArtistMusicBrainzTable
                .select(ArtistMusicBrainzTable.musicBrainzId)
                .where { ArtistMusicBrainzTable.artistId eq id }
                .singleOrNull()?.get(ArtistMusicBrainzTable.musicBrainzId)?.value
        }

        if (musicBrainzId != null) {
            val artistExists = dbQuery {
                MBArtistTable.select(MBArtistTable.id)
                    .where { MBArtistTable.id eq musicBrainzId }
                    .any()
            }

            if (!artistExists) {
                cachedMusicBrainzService.getArtist(musicBrainzId)
            }
        }

        dbQuery {
            ArtistMusicBrainzTable.upsert(ArtistMusicBrainzTable.artistId) {
                it[artistId] = id
                it[ArtistMusicBrainzTable.musicBrainzId] = musicBrainzId
                it[lastCheck] = Clock.System.now().toEpochMilliseconds()
            }
        }

        if ((musicBrainzId != null) && (musicBrainzId != oldMbId)) {
            if (oldMbId != null) {
                dbQuery {
                    ArtistTable.update({ ArtistTable.id eq id }) {
                        it[about] = ""
                        it[image] = null
                        it[lastImageCheck] = 0L
                        it[lastMetadataCheck] = 0L
                    }
                }
            }

            metadataFetchingService.refreshArtistMetadata(id)
        }

        return byId(id, userId)
    }

    suspend fun searchArtistOnMusicBrainz(query: String, page: Int, pageSize: Int, priority: HttpClientPriority = HttpClientPriority.NORMAL): PaginatedResponse<MusicBrainzArtist> {
        return musicBrainzService.searchArtistsMbPaged(query, page, pageSize, priority)
    }

    suspend fun byId(id: UUID, userId: UUID? = null): Artist? = querySingle(userId = userId) {
        where { ArtistTable.id eq id }
    }

    override suspend fun byMusicBrainzId(mbId: PlatformUUID): List<Artist> = byMusicBrainzId(mbId, null)

    suspend fun byMusicBrainzId(mbId: UUID, userId: UUID? = null): List<Artist> =
        queryArtists(0, Int.MAX_VALUE, userId = userId) {
            where { ArtistMusicBrainzTable.musicBrainzId eq mbId }
        }.data

    suspend fun byMusicBrainzIds(@LogParam("size") mbIds: Collection<PlatformUUID>, userId: UUID? = null): List<Artist> =
        queryArtists(0, Int.MAX_VALUE, userId = userId) {
            where { ArtistMusicBrainzTable.musicBrainzId inList mbIds }
        }.data

    suspend fun byIds(@LogParam("size") ids: List<UUID>, userId: UUID? = null): List<Artist> = queryArtists(0, Int.MAX_VALUE, userId = userId) {
        where { ArtistTable.id inList ids }
    }.data

    suspend fun rankedSearch(page: Int, pageSize: Int, query: String, userId: UUID? = null): PaginatedResponse<Artist> =
        queryArtists(page, pageSize, userId = userId, columnSet = {
            leftJoin(artistGroupJoinAlias, onColumn = { ArtistTable.id }, otherColumn = { artistGroupJoinAlias[ArtistMemberTable.artistId] })
                .leftJoin(artistGroupAlias, onColumn = { artistGroupJoinAlias[ArtistMemberTable.groupId] }, otherColumn = { artistGroupAlias[ArtistTable.id] })
                .leftJoin(artistMemberJoinAlias, onColumn = { ArtistTable.id }, otherColumn = { artistMemberJoinAlias[ArtistMemberTable.groupId] })
                .leftJoin(artistMemberAlias, onColumn = { artistMemberJoinAlias[ArtistMemberTable.artistId] }, otherColumn = { artistMemberAlias[ArtistTable.id] })
                .withMBArtistSearch()
        }) {
            rankedSearchQuery(
                query,
                listOf(10, 8, 6, 6, 5, 5, 3),
                listOf(
                    ArtistTable.name,
                    ArtistAliasTable.name,
                    artistGroupAlias[ArtistTable.name],
                    artistMemberAlias[ArtistTable.name]
                ) + mbArtistSearchColumns,
                ArtistTable.id,
                searchVectorColumn = if (searchIndexWorker != null) ArtistTable.searchVector else null
            )
        }

    suspend fun byGroup(page: Int, pageSize: Int, groupId: UUID, userId: UUID? = null): PaginatedResponse<Artist> =
        queryArtists(page, pageSize, userId = userId, columnSet = {
            innerJoin(ArtistMemberTable, onColumn = { ArtistTable.id }, otherColumn = { ArtistMemberTable.artistId })
        }) {
            where { ArtistMemberTable.groupId eq groupId }
        }

    suspend fun setGroup(id: UUID, artistIds: List<UUID>?, userId: UUID? = null): Artist? {
        dbQuery {
            ArtistTable.update({ ArtistTable.id eq id }) {
                it[isGroup] = artistIds != null
            }

            ArtistMemberTable.deleteWhere { ArtistMemberTable.groupId eq id }

            if (artistIds != null) {
                ArtistMemberTable.batchInsert(artistIds) { memberId ->
                    this[ArtistMemberTable.groupId] = id
                    this[ArtistMemberTable.artistId] = memberId
                }
            }
        }
        return byId(id, userId)
    }

    suspend fun mergeArtists(mergeArtists: MergeArtists, userId: UUID? = null): Artist? = dbQuery {
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
                    ).values.firstOrNull()
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

        val existingMbIds = ArtistMusicBrainzTable
            .select(ArtistMusicBrainzTable.musicBrainzId)
            .where { ArtistMusicBrainzTable.artistId inList currentArtistIds }
            .mapNotNull { it[ArtistMusicBrainzTable.musicBrainzId]?.value }
            .distinct()
            
        if (existingMbIds.isNotEmpty()) {
            ArtistMusicBrainzTable.insert { 
                it[artistId] = newArtist
                it[musicBrainzId] = existingMbIds.first()
            }
        }

        val existingGenreIds = ArtistGenreTable
            .select(ArtistGenreTable.genreId)
            .where { ArtistGenreTable.artistId inList currentArtistIds }
            .map { it[ArtistGenreTable.genreId].value }
            .distinct()

        if (existingGenreIds.isNotEmpty()) {
            ArtistGenreTable.batchInsert(existingGenreIds) { genreId ->
                this[ArtistGenreTable.artistId] = newArtist
                this[ArtistGenreTable.genreId] = genreId
            }
        }

        SongArtistTable.deleteWhere { SongArtistTable.artistId inList currentArtistIds }
        AlbumArtistTable.deleteWhere { AlbumArtistTable.artistId inList currentArtistIds }

        ArtistTable.deleteWhere { ArtistTable.id inList currentArtistIds }
        ArtistAliasTable.deleteWhere { ArtistAliasTable.artistId inList currentArtistIds }
        ArtistMusicBrainzTable.deleteWhere { ArtistMusicBrainzTable.artistId inList currentArtistIds }
        ArtistGenreTable.deleteWhere { ArtistGenreTable.artistId inList currentArtistIds }

        logger.info("Merged artists $mergeArtists into $newArtist")

        return@dbQuery byId(newArtist, userId)
    }

    suspend fun splitArtist(splitArtist: SplitArtist, userId: UUID? = null): List<Artist> {
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

        return byIds(finalTargetIds, userId)
    }

    suspend fun allArtists(page: Int, pageSize: Int, userId: UUID? = null): PaginatedResponse<Artist> =
        queryArtists(page, pageSize, userId = userId)

    suspend fun byColor(
        page: Int,
        pageSize: Int,
        color: Int,
        range: Int,
        userId: UUID? = null
    ): PaginatedResponse<Artist> {
        val (l, a, b) = ColorUtils.rgbToLab((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
        return queryArtists(page, pageSize, userId = userId, columnSet = {
            leftJoin(ImageMetadataTable, onColumn = { ArtistTable.image }, otherColumn = { ImageMetadataTable.imageId })
        }) {
            filterByColor(l, a, b, range)
            orderByColorDistance(l, a, b)
        }
    }

    suspend fun createArtist(
        name: String,
        isGroup: Boolean = false,
        about: String = "",
        musicBrainzId: UUID? = null,
        userId: UUID? = null
    ): Artist = dbQuery {
        val newId = ArtistTable.insertAndGetId {
            it[ArtistTable.name] = name
            it[ArtistTable.isGroup] = isGroup
            it[ArtistTable.about] = about
        }.value

        if (musicBrainzId != null) {
            if (MBArtistTable.selectAll().where { MBArtistTable.id eq musicBrainzId }.empty()) {
                MBArtistTable.insert {
                    it[id] = EntityID(musicBrainzId, MBArtistTable)
                    it[MBArtistTable.name] = name
                    it[MBArtistTable.sortName] = name
                }
            }

            ArtistMusicBrainzTable.insert {
                it[artistId] = newId
                it[ArtistMusicBrainzTable.musicBrainzId] = musicBrainzId
                it[lastCheck] = Clock.System.now().toEpochMilliseconds()
            }
        }

        byId(newId, userId)!!
    }

    fun allArtistIds(): Flow<UUID> = flow {
        ArtistTable
            .select(ArtistTable.id)
            .fetchBatchedResultsByIdKeyset(ArtistTable.id, 1000) { batch ->
                for (row in batch) {
                    emit(row[ArtistTable.id].value)
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun allArtistsFlow(): Flow<Artist> = allArtistIds().chunked(100).flatMapConcat { ids ->
        byIds(ids).asFlow()
    }

    fun artistIdsWithoutMusicBrainzId(): Flow<UUID> = flow {
        val oneWeekAgo = Clock.System.now() - 7.days

        ArtistTable
            .leftJoin(ArtistMusicBrainzTable)
            .select(ArtistTable.id)
            .where {
                ArtistMusicBrainzTable.artistId.isNull() or
                        (ArtistMusicBrainzTable.lastCheck eq 0L) or
                        (ArtistMusicBrainzTable.musicBrainzId.isNull() and (ArtistMusicBrainzTable.lastCheck less oneWeekAgo.toEpochMilliseconds()))
            }
            .fetchBatchedResultsByIdKeyset(ArtistTable.id, 1000) { batch ->
                for (row in batch) {
                    emit(row[ArtistTable.id].value)
                }
            }
    }

    suspend fun searchArtistImages(
        type: IMetadataService.MetadataType,
        query: String,
        limit: Int
    ): List<IMetadataService.Image> {
        val service = MetadataService.getMetadataService(type, environment)
        return try {
            val artists = service.searchArtists(query, 20)
            artists.flatMap { it.images }.distinctBy { it.url }.take(limit)
        } catch (e: Exception) {
            logger.error("Error searching artist images for $query using ${type.value}", e)
            emptyList()
        }
    }

    suspend fun setArtistImageByUrl(id: UUID, url: String, userId: UUID? = null): Artist? {
        val imageService by inject<ImageService>()

        val imageBytes = ApiClient.instance.safeQueuedGet<ByteArray>(url, HttpClientPriority.HIGH) ?: return null
        val imageId = imageService.createBatch(
            listOf(
                InsertableImage(
                    data = imageBytes,
                    imageHash = imageBytes.sha256(),
                    origin = url
                )
            )
        ).values.firstOrNull() ?: return null

        dbQuery {
            ArtistTable.update({ ArtistTable.id eq id }) {
                it[image] = EntityID(imageId, ImageTable)
                it[lastImageCheck] = System.currentTimeMillis()
            }
        }

        return byId(id, userId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun artistsWithoutMusicBrainzIdFlow(userId: UUID? = null): Flow<Artist> = artistIdsWithoutMusicBrainzId().chunked(100).flatMapConcat { ids ->
        byIds(ids, userId).asFlow()
    }

    suspend fun aliases(id: UUID): List<ArtistAlias> = dbQuery {
        ArtistAliasTable
            .selectAll()
            .where { ArtistAliasTable.artistId eq id }
            .map { ArtistAlias(it[ArtistAliasTable.artistId].value, it[ArtistAliasTable.name]) }
    }

    suspend fun addAlias(artistId: UUID, name: String): Boolean = dbQuery {
        val exists = ArtistAliasTable
            .selectAll()
            .where { (ArtistAliasTable.artistId eq artistId) and (ArtistAliasTable.name eq name) }
            .any()

        if (exists) return@dbQuery false

        ArtistAliasTable.insert {
            it[this.artistId] = artistId
            it[this.name] = name
        }
        true
    }

    suspend fun removeAlias(artistId: UUID, name: String): Boolean = dbQuery {
        ArtistAliasTable.deleteWhere {
            (ArtistAliasTable.artistId eq artistId) and (ArtistAliasTable.name eq name)
        } > 0
    }

    private suspend fun querySingle(
        userId: UUID? = null,
        query: Query.() -> Query
    ) = queryArtists(0, Int.MAX_VALUE, userId = userId, query = query).data.singleOrNull()

    private suspend fun queryArtists(
        page: Int,
        pageSize: Int,
        userId: UUID? = null,
        columnSet: ColumnSet.() -> ColumnSet = { this },
        query: Query.() -> Query = { this }
    ) = dbQuery {
        val baseSelect = ArtistTable
            .followedArtist(userId)
            .leftJoin(ArtistAliasTable)
            .leftJoin(ArtistMusicBrainzTable)
            .leftJoin(ArtistGenreTable)
            .leftJoin(GenreTable)
            .leftJoin(ImageTable, onColumn = { ArtistTable.image }, otherColumn = { ImageTable.id })
            .columnSet()
            .selectAll()
            .query()

        val countExpression = ArtistTable.id.countDistinct()
        val countQuery = Query(Slice(baseSelect.set.source, listOf(countExpression)), baseSelect.where)
        baseSelect.having?.let { h -> countQuery.having { h } }
        val total = SearchContext.redisTotal ?: countQuery.first()[countExpression]
        SearchContext.clear()

        if (total == 0L) return@dbQuery PaginatedResponse(
            data = listOf(),
            total = 0,
            page = page,
            pageSize = pageSize,
        )

        val sortAliases = baseSelect.orderByExpressions.mapIndexed { index, (expr, _) ->
            expr.alias("sort_$index")
        }
        val idQuery = Query(Slice(baseSelect.set.source, listOf(ArtistTable.id) + sortAliases), baseSelect.where)
        baseSelect.having?.let { h -> idQuery.having { h } }
        baseSelect.orderByExpressions.forEachIndexed { index, (_, order) ->
            idQuery.orderBy(sortAliases[index], order)
        }
        idQuery.withDistinct(true)

        if (pageSize != Int.MAX_VALUE) {
            idQuery.limit(pageSize)
            idQuery.offset((page * pageSize).toLong())
        }

        val ids = idQuery.map { it[ArtistTable.id].value }.distinct()

        if (ids.isEmpty()) return@dbQuery PaginatedResponse(
            data = listOf(),
            total = total.toInt(),
            page = page,
            pageSize = pageSize,
        )

        val mainArtistRows = ArtistTable
            .followedArtist(userId)
            .leftJoin(ArtistAliasTable)
            .leftJoin(ArtistMusicBrainzTable)
            .leftJoin(ArtistGenreTable)
            .leftJoin(GenreTable)
            .leftJoin(ImageTable, onColumn = { ArtistTable.image }, otherColumn = { ImageTable.id })
            .columnSet()
            .selectAll()
            .where { ArtistTable.id inList ids }
            .toList()

        val groupIds = mainArtistRows
            .filter { it[ArtistTable.isGroup] }
            .map { it[ArtistTable.id].value }
            .distinct()

        val memberDataRows = if (groupIds.isNotEmpty()) {
            ArtistTable
                .followedArtist(userId)
                .leftJoin(ArtistMusicBrainzTable)
                .leftJoin(ArtistGenreTable)
                .leftJoin(GenreTable)
                .leftJoin(ImageTable, onColumn = { ArtistTable.image }, otherColumn = { ImageTable.id })
                .innerJoin(ArtistMemberTable, onColumn = { ArtistTable.id }, otherColumn = { ArtistMemberTable.artistId })
                .selectAll()
                .where { ArtistMemberTable.groupId inList groupIds }
                .toList()
        } else {
            emptyList()
        }

        val unsortedData = mapEagerly(mainArtistRows, memberDataRows, userId).distinctBy { it.id }
        val data = ids.mapNotNull { id -> unsortedData.find { it.id == id } }

        PaginatedResponse(
            data = data,
            total = total.toInt(),
            page = page,
            pageSize = pageSize,
            hasNextPage = (page + 1).toLong() * pageSize < total,
        )
    }

    private fun mapEagerly(mainRows: List<ResultRow>, memberRows: List<ResultRow>, userId: UUID? = null): List<Artist> {
        val followedTable = if (userId != null) followedArtistAlias else FollowedArtistTable
        val genresByArtistId = (mainRows + memberRows)
            .mapNotNull { row ->
                val artistId = row.getOrNull(ArtistTable.id)?.value ?: return@mapNotNull null
                val genreId = row.getOrNull(GenreTable.id)?.value ?: return@mapNotNull null
                val genreName = row.getOrNull(GenreTable.name) ?: return@mapNotNull null
                artistId to Genre(genreId, genreName)
            }
            .distinct()
            .groupBy({ it.first }, { it.second })

        val membersByGroupId = memberRows
            .mapNotNull { row ->
                val groupId = row.getOrNull(ArtistMemberTable.groupId)?.value ?: return@mapNotNull null
                val artistId = row[ArtistTable.id].value
                val artist = mapArtist(row, genres = genresByArtistId[artistId] ?: listOf(), followedTable = followedTable)
                groupId to artist
            }
            .groupBy({ it.first }, { it.second })

        return mainRows.map { mainRow ->
            val artistId = mainRow[ArtistTable.id].value
            val genres = genresByArtistId[artistId] ?: listOf()
            val artist = mapArtist(mainRow, genres = genres, followedTable = followedTable)

            return@map if (artist.isGroup) {
                val memberArtists = membersByGroupId[artist.id]?.distinctBy { it.id } ?: listOf()
                artist.copy(artists = memberArtists)
            } else {
                artist
            }
        }
    }

    data class BulkCreateResult(val nameToIds: Map<String, List<UUID>>, val newlyCreated: Set<String>)

    suspend fun getOrBulkCreateWithResult(artistNames: List<String>): BulkCreateResult {
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

        return BulkCreateResult(existingSplits + existingMap + newMap, newNames.toSet())
    }

    suspend fun getOrBulkCreate(artistNames: List<String>): Map<String, List<UUID>> = getOrBulkCreateWithResult(artistNames).nameToIds

    suspend fun deleteUnreferencedArtists(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Int = dbQuery {
        val referencedArtists = mutableSetOf<UUID>()
        referencedArtists.addAll(SongArtistTable.selectAll().map { it[SongArtistTable.artistId].value })
        referencedArtists.addAll(AlbumArtistTable.selectAll().map { it[AlbumArtistTable.artistId].value })
        referencedArtists.addAll(ArtistMemberTable.selectAll().map { it[ArtistMemberTable.groupId].value })
        referencedArtists.addAll(ArtistMemberTable.selectAll().map { it[ArtistMemberTable.artistId].value })

        val allArtists = ArtistTable.selectAll().map { it[ArtistTable.id].value }

        val unreferencedArtists = allArtists.filter { it !in referencedArtists }
        onProgress(0.0, "Found ${unreferencedArtists.size} unreferenced artists")

        val chunks = unreferencedArtists.chunked(5000)
        chunks.forEachIndexed { index, batch ->
            val progress = (index.toDouble() / chunks.size) * 100.0
            onProgress(progress, "Deleting batch ${index + 1}/${chunks.size} (${batch.size} artists)")

            ArtistTable.deleteWhere { ArtistTable.id inList batch }
            ArtistAliasTable.deleteWhere { ArtistAliasTable.artistId inList batch }
        }
        
        onProgress(100.0, "Deleted ${unreferencedArtists.size} artists")
        logger.info("Deleted ${unreferencedArtists.size} unreferenced artists")
        unreferencedArtists.size
    }

    suspend fun upsertArtist(artist: Artist) = dbQuery {
        ArtistTable.upsert(ArtistTable.id) {
            it[id] = artist.id
            it[name] = artist.name
            it[isGroup] = artist.isGroup
            it[about] = artist.about
            it[image] = artist.imageId?.let { imageId -> EntityID(imageId, ImageTable) }
        }
        
        if (artist.musicbrainzId != null) {
            val mbId = artist.musicbrainzId!!
            if (MBArtistTable.selectAll().where { MBArtistTable.id eq mbId }.empty()) {
                MBArtistTable.insert {
                    it[id] = EntityID(mbId, MBArtistTable)
                    it[name] = artist.name
                    it[sortName] = artist.name
                }
            }

            ArtistMusicBrainzTable.upsert(ArtistMusicBrainzTable.artistId) {
                it[artistId] = artist.id
                it[musicBrainzId] = mbId
            }
        }

        artist.artists.forEach { member ->
            ArtistMemberTable.upsert(ArtistMemberTable.artistId, ArtistMemberTable.groupId) {
                it[artistId] = member.id
                it[groupId] = artist.id
            }
        }
    }

    suspend fun upsertArtistAlias(alias: ArtistAlias) = dbQuery {
        val exists = ArtistAliasTable.selectAll()
            .where { (ArtistAliasTable.artistId eq alias.artistId) and (ArtistAliasTable.name eq alias.name) }
            .any()
        
        if (!exists) {
            ArtistAliasTable.insert {
                it[artistId] = alias.artistId
                it[name] = alias.name
            }
        }
    }

    suspend fun upsertArtistSplitAlias(alias: ArtistSplitAlias) = dbQuery {
        ArtistSplitAliasTable.upsert(ArtistSplitAliasTable.name, ArtistSplitAliasTable.artistId) {
            it[artistId] = alias.artistId
            it[name] = alias.name
        }
    }
}
