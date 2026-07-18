package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.StatsRange
import dev.dertyp.db.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.*

class ListeningStatsServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: ListeningStatsService

    private val nowUtc: ZonedDateTime = ZonedDateTime.of(2025, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC)
    private val nowMs = nowUtc.toInstant().toEpochMilli()

    private fun setup(dialect: DbDialect) {
        startKoin { modules() }
        database = TestDatabase.connect(dialect, "listening_stats_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                AlbumTable,
                ArtistTable,
                ArtistAliasTable,
                SongTable,
                SongArtistTable,
                MBArtistTable,
                MBRecordingTable,
                MBRecordingArtistCreditTable,
                MBReleaseGroupTable,
                MBReleaseGroupCoverTable,
                MBReleaseTable,
                MBRecordingIsrcTable,
                SongMusicBrainzTable,
                AlbumMusicBrainzTable,
                ArtistMusicBrainzTable,
                ListenBrainzUserTable,
                UserListenBrainzLinkTable,
                ListenTable,
            )
        }
        service = ListeningStatsService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun at(daysAgo: Long, hour: Int = 12, minute: Int = 0): Long =
        nowUtc.minusDays(daysAgo).withHour(hour).withMinute(minute).toInstant().toEpochMilli()

    private fun insertUser(): UUID {
        val uid = UUID.randomUUID()
        UserTable.insert {
            it[id] = uid
            it[username] = "user_$uid"
            it[passwordHash] = "x"
        }
        return uid
    }

    private fun insertAlbum(name: String = "Album"): UUID {
        val aid = UUID.randomUUID()
        AlbumTable.insert {
            it[id] = aid
            it[AlbumTable.name] = name
        }
        return aid
    }

    private fun insertSong(albumId: UUID, title: String = "Song"): UUID {
        val sid = UUID.randomUUID()
        SongTable.insert {
            it[id] = sid
            it[SongTable.title] = title
            it[SongTable.albumId] = albumId
            it[fileSize] = 0
        }
        return sid
    }

    private fun insertArtist(name: String): UUID {
        val aid = UUID.randomUUID()
        ArtistTable.insert {
            it[id] = aid
            it[ArtistTable.name] = name
        }
        return aid
    }

    private fun linkSongArtist(songId: UUID, artistId: UUID) {
        SongArtistTable.insert {
            it[SongArtistTable.songId] = songId
            it[SongArtistTable.artistId] = artistId
        }
    }

    private fun insertLbUser(): UUID {
        val id = UUID.randomUUID()
        ListenBrainzUserTable.insert {
            it[ListenBrainzUserTable.id] = id
            it[username] = "lb_$id"
        }
        return id
    }

    private fun link(userId: UUID, lbUserId: UUID) {
        UserListenBrainzLinkTable.insert {
            it[UserListenBrainzLinkTable.userId] = userId
            it[listenBrainzUserId] = lbUserId
        }
    }

    private fun insertListen(
        at: Long,
        userId: UUID? = null,
        lbUserId: UUID? = null,
        songId: UUID? = null,
        recordingMbid: UUID? = null,
        releaseMbid: UUID? = null,
        isrcs: String? = null,
        artistMbids: String? = null,
        trackName: String? = null,
        artistName: String? = null,
        releaseName: String? = null,
    ) {
        ListenTable.insert {
            it[ListenTable.userId] = userId
            it[listenBrainzUserId] = lbUserId
            it[ListenTable.songId] = songId
            it[ListenTable.recordingMbid] = recordingMbid
            it[ListenTable.releaseMbid] = releaseMbid
            it[ListenTable.isrcs] = isrcs
            it[ListenTable.artistMbids] = artistMbids
            it[ListenTable.trackName] = trackName
            it[ListenTable.artistName] = artistName
            it[ListenTable.releaseName] = releaseName
            it[listenedAt] = at
            it[listenSource] = if (lbUserId != null) ListenSource.LISTENBRAINZ else ListenSource.LOCAL
        }
    }

    private suspend fun stats(
        userId: UUID,
        range: StatsRange = StatsRange.ALL_TIME,
        timezone: String = "UTC",
        topLimit: Int = 10,
    ) = service.stats(userId, range, timezone, topLimit, nowMs)

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the same play captured by both scrobblers counts once`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
            val song = insertSong(insertAlbum())
            insertListen(at(1), lbUserId = lb, songId = song)
            insertListen(at(1) + 1000, userId = u, songId = song)
            u
        }

        val result = stats(user)

        assertEquals(1L, result.listenCount)
        assertEquals(1, result.uniqueSongs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `cross-source duplicates collapse via recording MBID and ISRC`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
            val album = insertAlbum()
            val mbid = UUID.randomUUID()
            insertListen(at(1), lbUserId = lb, songId = insertSong(album), recordingMbid = mbid)
            insertListen(at(1) + 500, userId = u, songId = insertSong(album), recordingMbid = mbid)
            insertListen(at(2), lbUserId = lb, songId = insertSong(album), isrcs = "US1111111111,US2222222222")
            insertListen(at(2) + 500, userId = u, songId = insertSong(album), isrcs = "US2222222222")
            u
        }

        val result = stats(user)

        assertEquals(2L, result.listenCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unmatched listens count and are not deduped by shared null songId`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
            insertListen(at(1), lbUserId = lb, trackName = "Track A", artistName = "Artist A")
            insertListen(at(1) + 500, lbUserId = lb, trackName = "Track B", artistName = "Artist B")
            u
        }

        val result = stats(user)

        assertEquals(2L, result.listenCount)
        assertEquals(2, result.uniqueSongs)
        assertEquals(setOf("Track A", "Track B"), result.topSongs.map { it.title }.toSet())
        assertEquals(listOf(null, null), result.topSongs.map { it.songId })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unmatched listens group by normalized name and prefer MBID grouping`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
            insertListen(at(1), lbUserId = lb, trackName = "My Track", artistName = "My Artist")
            insertListen(at(2), lbUserId = lb, trackName = "my track ", artistName = "MY ARTIST")
            val mbid = UUID.randomUUID()
            insertListen(at(3), lbUserId = lb, trackName = "Other Name", recordingMbid = mbid)
            insertListen(at(4), lbUserId = lb, trackName = "Different Name", recordingMbid = mbid)
            u
        }

        val result = stats(user)

        assertEquals(4L, result.listenCount)
        assertEquals(2, result.uniqueSongs)
        val named = result.topSongs.single { it.title == "my track" }
        assertEquals(2L, named.listenCount)
        assertNull(named.songId)
        val byMbid = result.topSongs.single { it.title == "Different Name" }
        assertEquals(2L, byMbid.listenCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `day boundaries follow the requested timezone`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            insertListen(at(1, hour = 23, minute = 30), userId = u, songId = song)
            u
        }

        assertEquals(0L, stats(user, StatsRange.DAY, "UTC").listenCount)
        assertEquals(1L, stats(user, StatsRange.DAY, "Europe/Berlin").listenCount)
        assertEquals(0L, stats(user, StatsRange.DAY, "Not/AZone").listenCount)
        assertEquals("Z", stats(user, StatsRange.DAY, "Not/AZone").timezone)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `comparison reports previous range count and percent change`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            insertListen(at(3), userId = u, songId = song)
            insertListen(at(4), userId = u, songId = song)
            insertListen(at(5), userId = u, songId = song)
            insertListen(at(10), userId = u, songId = song)
            insertListen(at(11), userId = u, songId = song)
            u
        }

        val result = stats(user, StatsRange.WEEK)

        assertEquals(3L, result.listenCount)
        assertEquals(2L, result.comparison?.previousCount)
        assertEquals(50.0, result.comparison?.percentChange)
        assertNull(stats(user, StatsRange.ALL_TIME).comparison)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `empty previous range yields null percent change`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            insertListen(at(0, hour = 10), userId = u, songId = insertSong(insertAlbum()))
            u
        }

        val result = stats(user, StatsRange.DAY)

        assertEquals(1L, result.listenCount)
        assertEquals(0L, result.comparison?.previousCount)
        assertNull(result.comparison?.percentChange)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `top lists resolve library metadata and credit all artists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val album = insertAlbum("Best Album")
            val song = insertSong(album, title = "Best Song")
            linkSongArtist(song, insertArtist("Artist One"))
            linkSongArtist(song, insertArtist("Artist Two"))
            insertListen(at(1), userId = u, songId = song)
            insertListen(at(2), userId = u, songId = song)
            insertListen(at(3), userId = u, songId = song)
            u
        }

        val result = stats(user)

        val topSong = result.topSongs.single()
        assertEquals("Best Song", topSong.title)
        assertEquals("Artist One, Artist Two", topSong.artistName)
        assertEquals("Best Album", topSong.albumName)
        assertEquals(3L, topSong.listenCount)
        assertEquals(setOf("Artist One" to 3L, "Artist Two" to 3L), result.topArtists.map { it.name to it.listenCount }.toSet())
        assertEquals("Best Album" to 3L, result.topAlbums.single().let { it.name to it.listenCount })
        assertEquals(1, result.uniqueSongs)
        assertEquals(2, result.uniqueArtists)
        assertEquals(1, result.uniqueAlbums)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `song artist name uses MusicBrainz join phrases when available`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum(), title = "Collab")
            linkSongArtist(song, insertArtist("Artist One"))
            linkSongArtist(song, insertArtist("Artist Two"))

            val recordingId = UUID.randomUUID()
            MBRecordingTable.insert {
                it[id] = recordingId
                it[title] = "Collab"
            }
            val mbArtistOne = UUID.randomUUID()
            val mbArtistTwo = UUID.randomUUID()
            MBArtistTable.insert {
                it[id] = mbArtistOne
                it[name] = "Artist One"
                it[sortName] = "One, Artist"
            }
            MBArtistTable.insert {
                it[id] = mbArtistTwo
                it[name] = "Artist Two"
                it[sortName] = "Two, Artist"
            }
            MBRecordingArtistCreditTable.insert {
                it[MBRecordingArtistCreditTable.recordingId] = recordingId
                it[artistId] = mbArtistOne
                it[name] = "Artist One"
                it[joinPhrase] = " feat. "
                it[position] = 0
            }
            MBRecordingArtistCreditTable.insert {
                it[MBRecordingArtistCreditTable.recordingId] = recordingId
                it[artistId] = mbArtistTwo
                it[name] = "Artist Two"
                it[position] = 1
            }
            SongMusicBrainzTable.insert {
                it[songId] = song
                it[musicBrainzId] = recordingId
            }

            insertListen(at(1), userId = u, songId = song)
            u
        }

        val result = stats(user)

        assertEquals("Artist One feat. Artist Two", result.topSongs.single().artistName)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unmatched listens resolve library artists and albums via MBIDs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, artist, album) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
            val artist = insertArtist("Lib Artist")
            val mbArtist = UUID.randomUUID()
            MBArtistTable.insert {
                it[id] = mbArtist
                it[name] = "Lib Artist"
                it[sortName] = "Artist, Lib"
            }
            ArtistMusicBrainzTable.insert {
                it[artistId] = artist
                it[musicBrainzId] = mbArtist
            }
            val album = insertAlbum("Lib Album")
            val mbRelease = UUID.randomUUID()
            MBReleaseTable.insert {
                it[id] = mbRelease
                it[title] = "Lib Album"
            }
            AlbumMusicBrainzTable.insert {
                it[albumId] = album
                it[musicBrainzId] = mbRelease
            }
            val song = insertSong(album, title = "Lib Song")
            linkSongArtist(song, artist)
            insertListen(
                at(1),
                lbUserId = lb,
                trackName = "Foreign Track",
                artistName = "Some Spelling",
                artistMbids = mbArtist.toString(),
                releaseMbid = mbRelease,
                releaseName = "Some Album Spelling",
            )
            insertListen(at(2), userId = u, songId = song)
            Triple(u, artist, album)
        }

        val result = stats(user)

        assertEquals(2L, result.listenCount)
        val topArtist = result.topArtists.single()
        assertEquals(artist, topArtist.artistId)
        assertEquals("Lib Artist", topArtist.name)
        assertEquals(2L, topArtist.listenCount)
        val topAlbum = result.topAlbums.single()
        assertEquals(album, topAlbum.albumId)
        assertEquals("Lib Album", topAlbum.name)
        assertEquals(2L, topAlbum.listenCount)
        assertEquals(1, result.uniqueArtists)
        assertEquals(1, result.uniqueAlbums)
        assertEquals(2, result.uniqueSongs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unmatched entries surface MusicBrainz release group covers`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, imageId) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
            val imageId = UUID.randomUUID()
            ImageTable.insert {
                it[id] = imageId
                it[path] = "cover.jpg"
                it[imageHash] = "hash"
                it[origin] = "https://coverartarchive.org/release-group/x/front"
            }
            val groupId = UUID.randomUUID()
            MBReleaseGroupTable.insert {
                it[id] = groupId
                it[title] = "Foreign Album"
            }
            MBReleaseGroupCoverTable.insert {
                it[releaseGroupId] = groupId
                it[MBReleaseGroupCoverTable.imageId] = imageId
            }
            val releaseId = UUID.randomUUID()
            MBReleaseTable.insert {
                it[id] = releaseId
                it[title] = "Foreign Album"
                it[releaseGroupId] = groupId
            }
            insertListen(
                at(1),
                lbUserId = lb,
                trackName = "Foreign Track",
                releaseMbid = releaseId,
                releaseName = "Foreign Album",
            )
            u to imageId
        }

        val result = stats(user)

        assertEquals(imageId, result.topSongs.single().coverId)
        val topAlbum = result.topAlbums.single()
        assertNull(topAlbum.albumId)
        assertEquals(imageId, topAlbum.coverId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `streaks track current and longest runs of listening days`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            for (daysAgo in longArrayOf(0, 1, 2, 6, 7, 8, 9)) {
                insertListen(at(daysAgo), userId = u, songId = song)
            }
            u
        }

        val result = stats(user)

        assertEquals(3, result.streaks.currentStreakDays)
        assertEquals(4, result.streaks.longestStreakDays)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a run ending yesterday still counts as the current streak`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            insertListen(at(1), userId = u, songId = song)
            insertListen(at(2), userId = u, songId = song)
            u
        }

        assertEquals(2, stats(user).streaks.currentStreakDays)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `discoveries only include songs and artists first heard within the range`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val oldSong = insertSong(album, title = "Old Song")
            linkSongArtist(oldSong, insertArtist("Old Artist"))
            val newSong = insertSong(album, title = "New Song")
            linkSongArtist(newSong, insertArtist("New Artist"))
            insertListen(at(40), userId = u, songId = oldSong)
            insertListen(at(2), userId = u, songId = oldSong)
            insertListen(at(3), userId = u, songId = newSong)
            u
        }

        val result = stats(user, StatsRange.WEEK)

        assertEquals(setOf("Old Song", "New Song"), result.topSongs.map { it.title }.toSet())
        assertEquals(listOf("New Song"), result.discoveries.songs.map { it.title })
        assertEquals(listOf("New Artist"), result.discoveries.artists.map { it.name })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listen clock buckets hours and weekdays in the requested timezone`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            insertListen(at(0, hour = 10, minute = 30), userId = u, songId = insertSong(insertAlbum()))
            u
        }

        val result = stats(user, StatsRange.DAY, "Europe/Berlin")

        assertEquals(1L, result.listenClock.hourOfDay[12])
        assertEquals(1L, result.listenClock.dayOfWeek[6])
        assertEquals(23L, result.listenClock.hourOfDay.count { it == 0L }.toLong())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `only the requesting user's listens are aggregated`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val other = insertUser()
            val otherLb = insertLbUser()
            link(other, otherLb)
            val song = insertSong(insertAlbum())
            insertListen(at(1), userId = other, songId = song)
            insertListen(at(2), lbUserId = otherLb, songId = song)
            u
        }

        val result = stats(user)

        assertEquals(0L, result.listenCount)
        assertEquals(0, result.uniqueSongs)
        assertEquals(0, result.streaks.currentStreakDays)
    }
}
