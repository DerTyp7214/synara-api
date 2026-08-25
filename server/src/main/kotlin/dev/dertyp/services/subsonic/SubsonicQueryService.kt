package dev.dertyp.services.subsonic

import dev.dertyp.db.AlbumArtistTable
import dev.dertyp.db.AlbumGenreTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.FollowedArtistTable
import dev.dertyp.db.GenreTable
import dev.dertyp.db.ListenTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongGenreTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserAlbumTable
import dev.dertyp.db.UserPlaylistTable
import dev.dertyp.dbQuery
import dev.dertyp.services.Service
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.util.UUID

enum class AlbumListType(val key: String) {
    NEWEST("newest"),
    RANDOM("random"),
    ALPHABETICAL_BY_NAME("alphabeticalByName"),
    ALPHABETICAL_BY_ARTIST("alphabeticalByArtist"),
    BY_GENRE("byGenre"),
    BY_YEAR("byYear"),
    FREQUENT("frequent"),
    RECENT("recent"),
    STARRED("starred");

    companion object {
        fun fromKey(key: String): AlbumListType? = entries.firstOrNull { it.key == key }
    }
}

class SubsonicQueryService : Service() {
    suspend fun albumIds(
        type: AlbumListType,
        size: Int,
        offset: Long,
        genre: String?,
        fromYear: Int?,
        toYear: Int?,
        userId: UUID,
    ): List<UUID> = dbQuery {
        when (type) {
            AlbumListType.NEWEST -> {
                val latest = SongTable.inserted.max().alias("latest")
                SongTable.select(SongTable.albumId, latest)
                    .where { SongTable.albumId.isNotNull() }
                    .groupBy(SongTable.albumId)
                    .orderBy(latest, SortOrder.DESC)
                    .limit(size).offset(offset)
                    .mapNotNull { it[SongTable.albumId].value }
            }

            AlbumListType.RANDOM -> AlbumTable.select(AlbumTable.id)
                .orderBy(Random())
                .limit(size).offset(offset)
                .map { it[AlbumTable.id].value }

            AlbumListType.ALPHABETICAL_BY_NAME ->
                AlbumTable.select(AlbumTable.id)
                    .orderBy(AlbumTable.name, SortOrder.ASC)
                    .limit(size).offset(offset)
                    .map { it[AlbumTable.id].value }

            AlbumListType.ALPHABETICAL_BY_ARTIST -> {
                val artistName = ArtistTable.name.min().alias("artistName")
                AlbumTable
                    .leftJoin(AlbumArtistTable)
                    .leftJoin(ArtistTable, onColumn = { AlbumArtistTable.artistId }, otherColumn = { ArtistTable.id })
                    .select(AlbumTable.id, artistName)
                    .groupBy(AlbumTable.id, AlbumTable.name)
                    .orderBy(artistName, SortOrder.ASC_NULLS_LAST)
                    .orderBy(AlbumTable.name, SortOrder.ASC)
                    .limit(size).offset(offset)
                    .map { it[AlbumTable.id].value }
            }

            AlbumListType.BY_GENRE -> AlbumGenreTable.innerJoin(GenreTable)
                .select(AlbumGenreTable.albumId)
                .where { GenreTable.name eq (genre ?: "") }
                .limit(size).offset(offset)
                .map { it[AlbumGenreTable.albumId].value }

            AlbumListType.BY_YEAR -> {
                val from = fromYear ?: 0
                val to = toYear ?: 9999
                val year = AlbumTable.releaseDate.substring(1, 4)
                val query = AlbumTable.select(AlbumTable.id)
                    .where { year.between(minOf(from, to).toString().padStart(4, '0'), maxOf(from, to).toString().padStart(4, '0')) }
                    .orderBy(AlbumTable.releaseDate, if (to < from) SortOrder.DESC else SortOrder.ASC)
                query.limit(size).offset(offset).map { it[AlbumTable.id].value }
            }

            AlbumListType.FREQUENT -> {
                val listens = ListenTable.songId.count().alias("listens")
                ListenTable.innerJoin(SongTable)
                    .select(SongTable.albumId, listens)
                    .where { ListenTable.userId eq userId }
                    .andWhere { SongTable.albumId.isNotNull() }
                    .andWhere { ListenTable.qualifiedPlay }
                    .groupBy(SongTable.albumId)
                    .orderBy(listens, SortOrder.DESC)
                    .limit(size).offset(offset)
                    .mapNotNull { it[SongTable.albumId].value }
            }

            AlbumListType.RECENT -> {
                val lastListen = ListenTable.listenedAt.max().alias("lastListen")
                ListenTable.innerJoin(SongTable)
                    .select(SongTable.albumId, lastListen)
                    .where { ListenTable.userId eq userId }
                    .andWhere { SongTable.albumId.isNotNull() }
                    .groupBy(SongTable.albumId)
                    .orderBy(lastListen, SortOrder.DESC)
                    .limit(size).offset(offset)
                    .mapNotNull { it[SongTable.albumId].value }
            }

            AlbumListType.STARRED -> UserAlbumTable.select(UserAlbumTable.albumId)
                .where { UserAlbumTable.userId eq userId }
                .andWhere { UserAlbumTable.isFavourite eq true }
                .orderBy(UserAlbumTable.createdAt, SortOrder.DESC)
                .limit(size).offset(offset)
                .map { it[UserAlbumTable.albumId].value }
        }
    }

    suspend fun genresWithCounts(): List<GenreDto> = dbQuery {
        val songCounts = SongGenreTable.select(SongGenreTable.genreId, SongGenreTable.songId.count())
            .groupBy(SongGenreTable.genreId)
            .associate { it[SongGenreTable.genreId].value to it[SongGenreTable.songId.count()] }
        val albumCounts = AlbumGenreTable.select(AlbumGenreTable.genreId, AlbumGenreTable.albumId.count())
            .groupBy(AlbumGenreTable.genreId)
            .associate { it[AlbumGenreTable.genreId].value to it[AlbumGenreTable.albumId.count()] }
        GenreTable.selectAll().map {
            val id = it[GenreTable.id].value
            GenreDto(
                songCount = (songCounts[id] ?: 0L).toInt(),
                albumCount = (albumCounts[id] ?: 0L).toInt(),
                value = it[GenreTable.name],
            )
        }.sortedByDescending { it.songCount }
    }

    suspend fun songIdsByGenre(genre: String, count: Int, offset: Long): List<UUID> = dbQuery {
        SongGenreTable.innerJoin(GenreTable)
            .select(SongGenreTable.songId)
            .where { GenreTable.name eq genre }
            .limit(count).offset(offset)
            .map { it[SongGenreTable.songId].value }
    }

    suspend fun randomSongIds(size: Int, genre: String?, fromYear: Int?, toYear: Int?): List<UUID> = dbQuery {
        var query: Query = if (genre != null) {
            SongTable.innerJoin(SongGenreTable).innerJoin(GenreTable)
                .select(SongTable.id)
                .where { GenreTable.name eq genre }
        } else {
            SongTable.select(SongTable.id)
        }
        if (fromYear != null || toYear != null) {
            val year = SongTable.releaseDate.substring(1, 4)
            query = query.andWhere {
                year.between(
                    (fromYear ?: 0).toString().padStart(4, '0'),
                    (toYear ?: 9999).toString().padStart(4, '0'),
                )
            }
        }
        query.orderBy(Random()).limit(size).map { it[SongTable.id].value }
    }

    suspend fun starredAlbumStars(userId: UUID): Map<UUID, Long> = dbQuery {
        UserAlbumTable.select(UserAlbumTable.albumId, UserAlbumTable.createdAt)
            .where { UserAlbumTable.userId eq userId }
            .andWhere { UserAlbumTable.isFavourite eq true }
            .associate { it[UserAlbumTable.albumId].value to it[UserAlbumTable.createdAt] }
    }

    suspend fun setAlbumStar(userId: UUID, albumId: UUID, starred: Boolean) {
        dbQuery {
            UserAlbumTable.upsert(UserAlbumTable.userId, UserAlbumTable.albumId) {
                it[UserAlbumTable.userId] = userId
                it[UserAlbumTable.albumId] = albumId
                it[UserAlbumTable.isFavourite] = starred
                it[UserAlbumTable.updatedAt] = Instant.now().toEpochMilli()
            }
        }
    }

    suspend fun starredArtistIds(userId: UUID): List<UUID> = dbQuery {
        FollowedArtistTable.select(FollowedArtistTable.artistId)
            .where { FollowedArtistTable.userId eq userId }
            .map { it[FollowedArtistTable.artistId].value }
    }

    suspend fun setArtistStar(userId: UUID, artistId: UUID, starred: Boolean) {
        dbQuery {
            if (starred) {
                FollowedArtistTable.upsert(FollowedArtistTable.userId, FollowedArtistTable.artistId) {
                    it[FollowedArtistTable.userId] = userId
                    it[FollowedArtistTable.artistId] = artistId
                }
            } else {
                FollowedArtistTable.deleteWhere {
                    (FollowedArtistTable.userId eq userId) and (FollowedArtistTable.artistId eq artistId)
                }
            }
        }
    }

    suspend fun topSongIdsForArtist(artistId: UUID, limit: Int): List<UUID> = dbQuery {
        val listens = ListenTable.songId.count().alias("listens")
        ListenTable.innerJoin(SongTable)
            .innerJoin(SongArtistTable, { SongTable.id }, { SongArtistTable.songId })
            .select(SongTable.id, listens)
            .where { SongArtistTable.artistId eq artistId }
            .andWhere { ListenTable.qualifiedPlay }
            .groupBy(SongTable.id)
            .orderBy(listens, SortOrder.DESC)
            .limit(limit)
            .map { it[SongTable.id].value }
    }

    suspend fun updatePlaylistMeta(id: UUID, name: String?, comment: String?): Boolean = dbQuery {
        if (name == null && comment == null) return@dbQuery true
        UserPlaylistTable.update({ UserPlaylistTable.id eq id }) {
            if (name != null) it[UserPlaylistTable.name] = name
            if (comment != null) it[UserPlaylistTable.description] = comment
        } > 0
    }

    suspend fun songCount(): Long = dbQuery {
        SongTable.selectAll().count()
    }
}
