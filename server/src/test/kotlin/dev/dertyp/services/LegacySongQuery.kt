package dev.dertyp.services

import dev.dertyp.core.*
import dev.dertyp.core.db.dbQuery
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.services.AlbumService.Companion.calculateAlbumStats
import dev.dertyp.services.AlbumService.Companion.mapAlbum
import dev.dertyp.services.ArtistService.Companion.mapArtist
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.util.*

class LegacySongQuery(val songService: SongService) {
    companion object {
        const val SONG_DETAIL_CHUNK_SIZE = 5000
    }

    val albumArtistAlias = songService.albumArtistAlias
    val albumArtistMusicBrainzAlias = songService.albumArtistMusicBrainzAlias
    val albumArtistAliasAlias = songService.albumArtistAliasAlias
    val songCreditedAliasAlias = songService.songCreditedAliasAlias
    val albumCreditedAliasAlias = songService.albumCreditedAliasAlias
    val artistGroupAlias = songService.artistGroupAlias
    val artistMemberAlias = songService.artistMemberAlias
    val albumArtistGroupAlias = songService.albumArtistGroupAlias
    val albumArtistMemberAlias = songService.albumArtistMemberAlias
    val artistGroupJoinAlias = songService.artistGroupJoinAlias
    val artistMemberJoinAlias = songService.artistMemberJoinAlias
    val albumArtistGroupJoinAlias = songService.albumArtistGroupJoinAlias
    val albumArtistMemberJoinAlias = songService.albumArtistMemberJoinAlias
    val albumFollowedArtistAlias = songService.albumFollowedArtistAlias
    val songImageAlias = songService.songImageAlias
    val albumImageAlias = songService.albumImageAlias
    val artistImageAlias = songService.artistImageAlias
    val albumArtistImageAlias = songService.albumArtistImageAlias
    val songAnimatedImageAlias = songService.songAnimatedImageAlias
    val songAnimatedFrameAlias = songService.songAnimatedFrameAlias
    val albumAnimatedImageAlias = songService.albumAnimatedImageAlias
    val albumAnimatedFrameAlias = songService.albumAnimatedFrameAlias

    fun ColumnSet.userSong(userId: UUID?) = if (userId != null) {
        leftJoin(
            UserSongTable,
            onColumn = { SongTable.id },
            otherColumn = { UserSongTable.songId },
            additionalConstraint = { UserSongTable.userId eq userId }
        )
    } else this

    suspend fun rankedSongSearch(
        redisSearchService: RedisSearchService,
        page: Int,
        pageSize: Int,
        query: String,
        explicit: Boolean,
        userId: UUID,
        searchVector: Boolean = false,
        scope: Query.() -> Query = { this }
    ): PaginatedResponse<UserSong> =
        querySongsRanked(page, pageSize, explicit, userId, columnSet = {
            withMBRecordingSearch()
                .withMBReleaseSearch()
                .withMBArtistSearch()
        }) {
            rankedSearchQuery(
                redisSearchService,
                query,
                listOf(20, 10, 5, 5, 5, 5, 3, 3, 3, 3, 5, 5, 3, 5, 5, 3, 8),
                listOf(
                    SongMusicBrainzTable.musicBrainzId.castTo<String?>(VarCharColumnType(36)),
                    SongTable.title,
                    ArtistTable.name,
                    AlbumTable.name,
                    ArtistAliasTable.name,
                    albumArtistAliasAlias[ArtistAliasTable.name],
                    artistGroupAlias[ArtistTable.name],
                    artistMemberAlias[ArtistTable.name],
                    albumArtistGroupAlias[ArtistTable.name],
                    albumArtistMemberAlias[ArtistTable.name],
                    mbRecordingSearchTable[MBRecordingTable.title],
                    mbReleaseSearchTable[MBReleaseTable.title],
                    mbReleaseSearchTable[MBReleaseTable.disambiguation],
                    mbArtistSearchTable[MBArtistTable.name],
                    mbArtistAliasSearchTable[MBArtistAliasTable.name],
                    mbArtistSearchTable[MBArtistTable.disambiguation],
                    SongTable.titleTags
                ),
                SongTable.id,
                searchVectorColumn = if (searchVector) SongTable.searchVector else null
            ).let { it.copy(query = it.query.scope()) }
        }

    suspend inline fun <reified T : BaseSong> querySingle(
        userId: UUID? = null,
        crossinline columnSet: ColumnSet.() -> ColumnSet = { this },
        crossinline query: Query.() -> Query = { this }
    ) =
        querySongs<T>(0, Int.MAX_VALUE, true, userId, columnSet, query).data.singleOrNull()

    suspend inline fun <reified T : BaseSong> querySongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        userId: UUID? = null,
        crossinline columnSet: ColumnSet.() -> ColumnSet = { this },
        crossinline query: Query.() -> Query = { this }
    ) = querySongsRanked<T>(page, pageSize, explicit, userId, columnSet) { RankedSearch(query()) }

    suspend inline fun <reified T : BaseSong> querySongsRanked(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        userId: UUID? = null,
        crossinline columnSet: ColumnSet.() -> ColumnSet = { this },
        crossinline search: Query.() -> RankedSearch
    ) = dbQuery {
        val base = SongTable
            .leftJoin(
                AlbumTable,
                onColumn = { SongTable.albumId },
                otherColumn = { AlbumTable.id }
            )
            .leftJoin(
                AlbumMusicBrainzTable,
                onColumn = { AlbumTable.id },
                otherColumn = { AlbumMusicBrainzTable.albumId }
            )
            .leftJoin(SongArtistTable)
            .leftJoin(
                ArtistTable,
                onColumn = { SongArtistTable.artistId },
                otherColumn = { ArtistTable.id }
            )
            .leftJoin(
                ArtistMusicBrainzTable,
                onColumn = { ArtistTable.id },
                otherColumn = { ArtistMusicBrainzTable.artistId }
            )
            .leftJoin(
                artistGroupJoinAlias,
                onColumn = { ArtistTable.id },
                otherColumn = { artistGroupJoinAlias[ArtistMemberTable.artistId] })
            .leftJoin(
                artistGroupAlias,
                onColumn = { artistGroupJoinAlias[ArtistMemberTable.groupId] },
                otherColumn = { artistGroupAlias[ArtistTable.id] })
            .leftJoin(
                artistMemberJoinAlias,
                onColumn = { ArtistTable.id },
                otherColumn = { artistMemberJoinAlias[ArtistMemberTable.groupId] })
            .leftJoin(
                artistMemberAlias,
                onColumn = { artistMemberJoinAlias[ArtistMemberTable.artistId] },
                otherColumn = { artistMemberAlias[ArtistTable.id] })
            .leftJoin(ArtistAliasTable)
            .leftJoin(
                songCreditedAliasAlias,
                onColumn = { SongArtistTable.creditedAliasId },
                otherColumn = { songCreditedAliasAlias[ArtistAliasTable.id] }
            )
            .leftJoin(
                AlbumArtistTable,
                onColumn = { AlbumTable.id },
                otherColumn = { AlbumArtistTable.albumId }
            )
            .leftJoin(
                albumArtistAlias,
                onColumn = { AlbumArtistTable.artistId },
                otherColumn = { albumArtistAlias[ArtistTable.id] }
            )
            .leftJoin(
                albumArtistMusicBrainzAlias,
                onColumn = { albumArtistAlias[ArtistTable.id] },
                otherColumn = { albumArtistMusicBrainzAlias[ArtistMusicBrainzTable.artistId] }
            )
            .leftJoin(
                albumArtistAliasAlias,
                onColumn = { AlbumArtistTable.artistId },
                otherColumn = { albumArtistAliasAlias[ArtistAliasTable.artistId] }
            )
            .leftJoin(
                albumCreditedAliasAlias,
                onColumn = { AlbumArtistTable.creditedAliasId },
                otherColumn = { albumCreditedAliasAlias[ArtistAliasTable.id] }
            )
            .leftJoin(
                albumArtistGroupJoinAlias,
                onColumn = { albumArtistAlias[ArtistTable.id] },
                otherColumn = { albumArtistGroupJoinAlias[ArtistMemberTable.artistId] })
            .leftJoin(
                albumArtistGroupAlias,
                onColumn = { albumArtistGroupJoinAlias[ArtistMemberTable.groupId] },
                otherColumn = { albumArtistGroupAlias[ArtistTable.id] })
            .leftJoin(
                albumArtistMemberJoinAlias,
                onColumn = { albumArtistAlias[ArtistTable.id] },
                otherColumn = { albumArtistMemberJoinAlias[ArtistMemberTable.groupId] })
            .leftJoin(
                albumArtistMemberAlias,
                onColumn = { albumArtistMemberJoinAlias[ArtistMemberTable.artistId] },
                otherColumn = { albumArtistMemberAlias[ArtistTable.id] })
            .leftJoin(SongGenreTable)
            .leftJoin(GenreTable)
            .leftJoin(SongProviderTable)
            .leftJoin(
                songImageAlias,
                onColumn = { SongTable.cover },
                otherColumn = { songImageAlias[ImageTable.id] })
            .leftJoin(
                albumImageAlias,
                onColumn = { AlbumTable.cover },
                otherColumn = { albumImageAlias[ImageTable.id] })
            .leftJoin(
                artistImageAlias,
                onColumn = { ArtistTable.image },
                otherColumn = { artistImageAlias[ImageTable.id] })
            .leftJoin(
                albumArtistImageAlias,
                onColumn = { albumArtistAlias[ArtistTable.image] },
                otherColumn = { albumArtistImageAlias[ImageTable.id] })
            .leftJoin(
                songAnimatedImageAlias,
                onColumn = { SongTable.animatedCover },
                otherColumn = { songAnimatedImageAlias[AnimatedImageTable.id] })
            .leftJoin(
                songAnimatedFrameAlias,
                onColumn = { songAnimatedImageAlias[AnimatedImageTable.imageId] },
                otherColumn = { songAnimatedFrameAlias[ImageTable.id] })
            .leftJoin(
                albumAnimatedImageAlias,
                onColumn = { AlbumTable.animatedCover },
                otherColumn = { albumAnimatedImageAlias[AnimatedImageTable.id] })
            .leftJoin(
                albumAnimatedFrameAlias,
                onColumn = { albumAnimatedImageAlias[AnimatedImageTable.imageId] },
                otherColumn = { albumAnimatedFrameAlias[ImageTable.id] })
            .leftJoin(SongMusicBrainzTable)
            .userSong(userId)
            .followedArtist(userId)
            .followedArtist(userId, albumFollowedArtistAlias, albumArtistAlias[ArtistTable.id])
            .columnSet()

        val ranked = base.selectAll().search()
        val q = ranked.query
        val paged = pageSize != Int.MAX_VALUE

        val countedTotal = ranked.redisTotal ?: if (paged) {
            val countExpression = SongTable.id.countDistinct()
            val countQuery = Query(Slice(q.set.source, listOf(countExpression)), q.where)
            q.having?.let { h -> countQuery.having { h } }
            countQuery.first()[countExpression]
        } else null

        if (countedTotal == 0L) return@dbQuery PaginatedResponse(
            data = listOf(),
            total = 0,
            page = page,
            pageSize = pageSize,
        )

        val sortAliases = q.orderByExpressions.mapIndexed { index, (expr, _) ->
            expr.alias("sort_$index")
        }
        val idQuery = Query(Slice(q.set.source, listOf(SongTable.id) + sortAliases), q.where)
        q.having?.let { h -> idQuery.having { h } }
        q.orderByExpressions.forEachIndexed { index, (_, order) ->
            idQuery.orderBy(sortAliases[index], order)
        }
        idQuery.withDistinct(true)

        if (paged) {
            idQuery.limit(pageSize)
            idQuery.offset((page * pageSize).toLong())
        }

        val ids = idQuery.map { it[SongTable.id].value }.distinct()
        val total = countedTotal ?: ids.size.toLong()

        if (ids.isEmpty()) return@dbQuery PaginatedResponse(
            data = listOf(),
            total = total.toInt(),
            page = page,
            pageSize = pageSize,
        )

        idOrderedPage(ids, loadSongs<T>(ids, userId, explicit), total, page, pageSize) { it.id }
    }

    suspend inline fun <reified T : BaseSong> loadSongs(
        ids: List<UUID>,
        userId: UUID?,
        explicit: Boolean,
    ): List<T> {
        val songIdChunks = ids.chunked(SONG_DETAIL_CHUNK_SIZE)

        val songRows = songIdChunks.flatMap { chunk ->
            SongTable
                .leftJoin(AlbumTable, onColumn = { SongTable.albumId }, otherColumn = { AlbumTable.id })
                .leftJoin(
                    AlbumMusicBrainzTable,
                    onColumn = { AlbumTable.id },
                    otherColumn = { AlbumMusicBrainzTable.albumId })
                .leftJoin(
                    songImageAlias,
                    onColumn = { SongTable.cover },
                    otherColumn = { songImageAlias[ImageTable.id] })
                .leftJoin(
                    albumImageAlias,
                    onColumn = { AlbumTable.cover },
                    otherColumn = { albumImageAlias[ImageTable.id] })
                .leftJoin(
                    songAnimatedImageAlias,
                    onColumn = { SongTable.animatedCover },
                    otherColumn = { songAnimatedImageAlias[AnimatedImageTable.id] })
                .leftJoin(
                    songAnimatedFrameAlias,
                    onColumn = { songAnimatedImageAlias[AnimatedImageTable.imageId] },
                    otherColumn = { songAnimatedFrameAlias[ImageTable.id] })
                .leftJoin(
                    albumAnimatedImageAlias,
                    onColumn = { AlbumTable.animatedCover },
                    otherColumn = { albumAnimatedImageAlias[AnimatedImageTable.id] })
                .leftJoin(
                    albumAnimatedFrameAlias,
                    onColumn = { albumAnimatedImageAlias[AnimatedImageTable.imageId] },
                    otherColumn = { albumAnimatedFrameAlias[ImageTable.id] })
                .leftJoin(
                    SongMusicBrainzTable,
                    onColumn = { SongTable.id },
                    otherColumn = { SongMusicBrainzTable.songId })
                .userSong(userId)
                .selectAll()
                .where { SongTable.id inList chunk }
                .toList()
        }

        val artistsBySong = songIdChunks.flatMap { chunk ->
            SongArtistTable
                .innerJoin(ArtistTable, onColumn = { SongArtistTable.artistId }, otherColumn = { ArtistTable.id })
                .leftJoin(
                    ArtistMusicBrainzTable,
                    onColumn = { ArtistTable.id },
                    otherColumn = { ArtistMusicBrainzTable.artistId })
                .leftJoin(
                    songCreditedAliasAlias,
                    onColumn = { SongArtistTable.creditedAliasId },
                    otherColumn = { songCreditedAliasAlias[ArtistAliasTable.id] })
                .leftJoin(
                    artistImageAlias,
                    onColumn = { ArtistTable.image },
                    otherColumn = { artistImageAlias[ImageTable.id] })
                .followedArtist(userId)
                .selectAll()
                .where { SongArtistTable.songId inList chunk }
                .toList()
        }.groupBy { it[SongArtistTable.songId].value }.mapValues { (_, rows) ->
            val positions = rows.associate { it[ArtistTable.id].value to it[SongArtistTable.position] }
            rows.map { row ->
                mapArtist(
                    row,
                    ArtistTable,
                    followedTable = followedArtistAlias,
                    blurHashColumn = artistImageAlias[ImageTable.blurHash]
                ).copy(
                    creditedName = row.getOrNull(songCreditedAliasAlias[ArtistAliasTable.name]),
                    joinPhrase = row[SongArtistTable.joinPhrase],
                )
            }.inCreditOrder { positions.getValue(it.id) }
        }

        val albumIds = songRows.map { it[SongTable.albumId].value }.distinct()

        val albumArtistsByAlbum = albumIds.chunked(SONG_DETAIL_CHUNK_SIZE).flatMap { chunk ->
            AlbumArtistTable
                .innerJoin(
                    albumArtistAlias,
                    onColumn = { AlbumArtistTable.artistId },
                    otherColumn = { albumArtistAlias[ArtistTable.id] })
                .leftJoin(
                    albumArtistMusicBrainzAlias,
                    onColumn = { albumArtistAlias[ArtistTable.id] },
                    otherColumn = { albumArtistMusicBrainzAlias[ArtistMusicBrainzTable.artistId] })
                .leftJoin(
                    albumCreditedAliasAlias,
                    onColumn = { AlbumArtistTable.creditedAliasId },
                    otherColumn = { albumCreditedAliasAlias[ArtistAliasTable.id] })
                .leftJoin(
                    albumArtistImageAlias,
                    onColumn = { albumArtistAlias[ArtistTable.image] },
                    otherColumn = { albumArtistImageAlias[ImageTable.id] })
                .followedArtist(userId, albumFollowedArtistAlias, albumArtistAlias[ArtistTable.id])
                .selectAll()
                .where { AlbumArtistTable.albumId inList chunk }
                .toList()
        }.groupBy { it[AlbumArtistTable.albumId].value }.mapValues { (_, rows) ->
            val positions =
                rows.associate { it[albumArtistAlias[ArtistTable.id]].value to it[AlbumArtistTable.position] }
            rows.map { row ->
                mapArtist(
                    row,
                    albumArtistAlias,
                    row.getOrNull(albumArtistMusicBrainzAlias[ArtistMusicBrainzTable.musicBrainzId])?.value,
                    followedTable = albumFollowedArtistAlias,
                    blurHashColumn = albumArtistImageAlias[ImageTable.blurHash]
                ).copy(
                    creditedName = row.getOrNull(albumCreditedAliasAlias[ArtistAliasTable.name]),
                    joinPhrase = row[AlbumArtistTable.joinPhrase],
                )
            }.inCreditOrder { positions.getValue(it.id) }
        }

        val genresBySong = songIdChunks.flatMap { chunk ->
            SongGenreTable
                .innerJoin(GenreTable, onColumn = { SongGenreTable.genreId }, otherColumn = { GenreTable.id })
                .select(SongGenreTable.songId, GenreTable.id, GenreTable.name)
                .where { SongGenreTable.songId inList chunk }
                .toList()
        }.groupBy({ it[SongGenreTable.songId].value }) { row ->
            Genre(row[GenreTable.id].value, row[GenreTable.name])
        }.mapValues { (_, genres) -> genres.inNameOrder() }

        val providersBySong = songIdChunks.flatMap { chunk ->
            SongProviderTable
                .select(
                    SongProviderTable.songId,
                    SongProviderTable.provider,
                    SongProviderTable.externalId,
                    SongProviderTable.rawUrl
                )
                .where { SongProviderTable.songId inList chunk }
                .toList()
        }.groupBy({ it[SongProviderTable.songId].value }) { row ->
            Triple(row[SongProviderTable.provider], row[SongProviderTable.externalId], row[SongProviderTable.rawUrl])
        }

        val statsByAlbumId = if (albumIds.isNotEmpty()) {
            calculateAlbumStats(albumIds)
        } else {
            emptyMap()
        }

        val loadedIds = songRows.map { it[SongTable.id].value }
        val insertedBySong = songRows.associate { it[SongTable.id].value to it[SongTable.inserted] }

        val variants = loadedIds.chunked(1000).flatMap { chunk ->
            SongVariantTable
                .selectAll()
                .where { SongVariantTable.songId inList chunk }
                .andWhere { SongVariantTable.kind eq SongVariantKind.ATMOS }
                .toList()
        }.associateBy { it[SongVariantTable.songId].value }

        val playbackTags = if (T::class == UserSong::class && userId != null) {
            TimecodeTagService.playbackTags(userId, loadedIds)
        } else {
            emptyMap()
        }

        return songRows.map { row ->
            val songId = row[SongTable.id].value
            val genres = genresBySong[songId].orEmpty()
            val album = mapAlbum(
                row,
                blurHashColumn = albumImageAlias[ImageTable.blurHash],
                animatedCoverImageIdColumn = albumAnimatedImageAlias[AnimatedImageTable.imageId],
                animatedCoverBlurHashColumn = albumAnimatedFrameAlias[ImageTable.blurHash],
            ).let { album ->
                album.copy(
                    artists = albumArtistsByAlbum[album.id].orEmpty(),
                    totalDuration = statsByAlbumId[album.id]?.first ?: -1L,
                    totalSize = statsByAlbumId[album.id]?.second ?: -1L
                )
            }
            val variant = variants[songId]
            val song = songService.map<T>(
                row, genres, songImageAlias[ImageTable.blurHash],
                animatedCoverImageIdColumn = songAnimatedImageAlias[AnimatedImageTable.imageId],
                animatedCoverBlurHashColumn = songAnimatedFrameAlias[ImageTable.blurHash],
            )
            val providers = providersBySong[songId].orEmpty()
            val originalUrl = providers.firstOrNull { it.third == song.originalUrl }?.third
                ?: providers.minWithOrNull(compareBy<Triple<String, String, String>> { it.first }.thenBy { it.second })?.third
                ?: song.originalUrl
            val artists = artistsBySong[songId].orEmpty()

            when (song) {
                is Song -> song.copy(
                    album = album,
                    artists = artists,
                    genres = genres,
                    originalUrl = originalUrl,
                    atmos = variant?.let(songService::mapVariant),
                    atmosVariantPath = variant?.get(SongVariantTable.path),
                ) as T

                is UserSong -> song.copy(
                    album = album,
                    artists = artists,
                    genres = genres,
                    originalUrl = originalUrl,
                    atmos = variant?.let(songService::mapVariant),
                    atmosVariantPath = variant?.get(SongVariantTable.path),
                    playbackTags = playbackTags[songId] ?: emptyList(),
                ) as T

                else -> throw Exception("Unknown song type: $song")
            }
        }.groupBy {
            listOf(
                it.title.removeSuffix("\uD83C\uDD74").trim(),
                it.tags,
                it.releaseDate,
                it.duration,
                it.trackNumber,
                it.discNumber,
                it.album?.name
            )
        }.mapNotNull { (_, candidates) ->
            val songList = candidates.sortedWith(compareBy<T> { insertedBySong[it.id] }.thenBy(uuidOrder) { it.id })
            if (explicit) songList.find { it.explicit } ?: songList.first()
            else songList.find { !it.explicit }
        }
    }
}
