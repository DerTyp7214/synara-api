package dev.dertyp.services.subsonic

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.AnimatedImageTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.ListenBrainzUserTable
import dev.dertyp.db.ListenSource
import dev.dertyp.db.ListenTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserAlbumTable
import dev.dertyp.db.UserTable
import dev.dertyp.dbQuery
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID
import kotlin.test.assertEquals

class SubsonicQueryServiceTest {
    private val userId = UUID.randomUUID()
    private val oldAlbum = UUID.randomUUID()
    private val midAlbum = UUID.randomUUID()
    private val newAlbum = UUID.randomUUID()

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "subsonic_query_test")
        dbQuery {
            SchemaUtils.create(
                UserTable, ImageTable, AnimatedImageTable, AlbumTable, SongTable,
                ListenBrainzUserTable, ListenTable, UserAlbumTable,
            )
            UserTable.insert {
                it[id] = userId
                it[username] = "tester"
                it[passwordHash] = "x"
            }
            listOf(
                Triple(oldAlbum, "Old", "1991-05-01"),
                Triple(midAlbum, "Mid", "2005-08-13"),
                Triple(newAlbum, "New", "2021-01-30"),
            ).forEach { (albumId, albumName, released) ->
                AlbumTable.insert {
                    it[id] = albumId
                    it[name] = albumName
                    it[releaseDate] = released
                }
            }
        }
    }

    private suspend fun insertSong(albumId: UUID, insertedAt: Long, durationMs: Long = 0): UUID {
        val songId = UUID.randomUUID()
        dbQuery {
            SongTable.insert {
                it[id] = songId
                it[title] = "song"
                it[SongTable.albumId] = albumId
                it[inserted] = insertedAt
                it[duration] = durationMs
            }
        }
        return songId
    }

    private suspend fun insertListens(songId: UUID, count: Int, lastAt: Long, playedMs: Long? = null) {
        dbQuery {
            repeat(count) { index ->
                ListenTable.insert {
                    it[ListenTable.userId] = this@SubsonicQueryServiceTest.userId
                    it[ListenTable.songId] = songId
                    it[listenedAt] = lastAt - index * 60_000
                    it[listenSource] = ListenSource.LOCAL
                    it[msPlayed] = playedMs
                }
            }
        }
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `newest orders albums by latest song insertion`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        insertSong(oldAlbum, 1_000)
        insertSong(midAlbum, 3_000)
        insertSong(newAlbum, 2_000)

        val service = SubsonicQueryService()
        val ids = service.albumIds(AlbumListType.NEWEST, 10, 0, null, null, null, userId)
        assertEquals(listOf(midAlbum, newAlbum, oldAlbum), ids)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `frequent orders albums by listen count for the user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val oldSong = insertSong(oldAlbum, 1_000)
        val newSong = insertSong(newAlbum, 2_000)
        insertListens(oldSong, 5, 10_000_000)
        insertListens(newSong, 2, 20_000_000)

        val service = SubsonicQueryService()
        assertEquals(
            listOf(oldAlbum, newAlbum),
            service.albumIds(AlbumListType.FREQUENT, 10, 0, null, null, null, userId),
        )
        assertEquals(
            listOf(newAlbum, oldAlbum),
            service.albumIds(AlbumListType.RECENT, 10, 0, null, null, null, userId),
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byYear filters on the release year range`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = SubsonicQueryService()

        assertEquals(
            listOf(oldAlbum, midAlbum),
            service.albumIds(AlbumListType.BY_YEAR, 10, 0, null, 1990, 2006, userId),
        )
        assertEquals(
            listOf(midAlbum, oldAlbum),
            service.albumIds(AlbumListType.BY_YEAR, 10, 0, null, 2006, 1990, userId),
        )
        assertEquals(
            listOf(newAlbum),
            service.albumIds(AlbumListType.BY_YEAR, 10, 0, null, 2010, 2022, userId),
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `starred albums round trip through setAlbumStar`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = SubsonicQueryService()

        service.setAlbumStar(userId, midAlbum, true)
        assertEquals(setOf(midAlbum), service.starredAlbumStars(userId).keys)

        service.setAlbumStar(userId, midAlbum, false)
        assertEquals(emptySet<UUID>(), service.starredAlbumStars(userId).keys)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `frequent ignores plays that are too short to count`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val skipped = insertSong(oldAlbum, 1_000, durationMs = 240_000)
        val halfPlayed = insertSong(midAlbum, 2_000, durationMs = 240_000)
        val longPlayed = insertSong(newAlbum, 3_000, durationMs = 600_000)
        insertListens(skipped, 10, 10_000_000, playedMs = 30_000)
        insertListens(halfPlayed, 2, 20_000_000, playedMs = 120_000)
        insertListens(longPlayed, 3, 30_000_000, playedMs = 180_000)

        val service = SubsonicQueryService()
        assertEquals(
            listOf(newAlbum, midAlbum),
            service.albumIds(AlbumListType.FREQUENT, 10, 0, null, null, null, userId),
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `top songs for an artist ignore plays that are too short to count`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        dbQuery {
            SchemaUtils.create(ArtistTable, SongArtistTable)
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "artist"
            }
        }
        val skipped = insertSong(oldAlbum, 1_000, durationMs = 240_000)
        val played = insertSong(oldAlbum, 2_000, durationMs = 240_000)
        dbQuery {
            listOf(skipped, played).forEach { sid ->
                SongArtistTable.insert {
                    it[songId] = sid
                    it[SongArtistTable.artistId] = artistId
                }
            }
        }
        insertListens(skipped, 10, 10_000_000, playedMs = 5_000)
        insertListens(played, 1, 20_000_000)

        assertEquals(listOf(played), SubsonicQueryService().topSongIdsForArtist(artistId, 5))
    }
}
