package dev.dertyp.mcp

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.UserSong
import dev.dertyp.db.*
import dev.dertyp.services.AlbumService
import dev.dertyp.services.ArtistService
import dev.dertyp.services.NowPlayingSnapshot
import dev.dertyp.services.ScrobbleService
import dev.dertyp.services.SongService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

class ListenHistoryQueryServiceTest {
    private lateinit var database: Database
    private lateinit var service: ListenHistoryQueryService
    private lateinit var songService: SongService
    private lateinit var scrobbleService: ScrobbleService
    private val songDtos = HashMap<UUID, UserSong>()

    private fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "listen_history_query_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                AlbumTable,
                ArtistTable,
                ArtistAliasTable,
                SongTable, SongVariantTable,
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
                ListenLinkTable,
            )
        }
        songDtos.clear()
        songService = mockk()
        coEvery { songService.byIds(any(), any()) } answers {
            val ids = firstArg<List<UUID>>()
            ids.mapNotNull { songDtos[it] }
        }
        scrobbleService = mockk()
        service = ListenHistoryQueryService(songService, mockk<ArtistService>(), mockk<AlbumService>(), scrobbleService)
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    private fun ms(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

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

    private fun insertArtist(name: String): UUID {
        val aid = UUID.randomUUID()
        ArtistTable.insert {
            it[id] = aid
            it[ArtistTable.name] = name
        }
        return aid
    }

    private fun insertSong(albumId: UUID, title: String = "Song", durationMs: Long = 0): UUID {
        val sid = UUID.randomUUID()
        SongTable.insert {
            it[id] = sid
            it[SongTable.title] = title
            it[SongTable.albumId] = albumId
            it[fileSize] = 0
            it[SongTable.duration] = durationMs
        }
        return sid
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
        playedMs: Long? = null,
        source: ListenSource? = null,
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
            it[listenSource] = source ?: if (lbUserId != null) ListenSource.LISTENBRAINZ else ListenSource.LOCAL
            it[msPlayed] = playedMs
        }
    }

    private fun registerSong(id: UUID, title: String, durationMs: Long = 0, artists: List<Artist> = emptyList(), album: Album? = null) {
        songDtos[id] = UserSong(
            id = id,
            title = title,
            artists = artists,
            album = album,
            duration = durationMs,
            explicit = false,
            path = "",
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listens returns newest first`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, t1, t2, t3) = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            registerSong(song, "Song")
            val a = ms(2024, 1, 1)
            val b = ms(2024, 1, 2)
            val c = ms(2024, 1, 3)
            insertListen(a, userId = u, songId = song)
            insertListen(b, userId = u, songId = song)
            insertListen(c, userId = u, songId = song)
            listOf(u, a, b, c)
        }

        val page = service.listens(user as UUID, ListenFilter(), 10, null, ZoneOffset.UTC)

        assertEquals(listOf(t3, t2, t1), page.listens.map { it.listenedAt.epochMs })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listens from is inclusive and to is exclusive`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val t1 = ms(2024, 1, 1)
        val t2 = ms(2024, 1, 2)
        val t3 = ms(2024, 1, 3)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            registerSong(song, "Song")
            insertListen(t1, userId = u, songId = song)
            insertListen(t2, userId = u, songId = song)
            insertListen(t3, userId = u, songId = song)
            u
        }

        val page = service.listens(user, ListenFilter(from = t2, to = t3), 10, null, ZoneOffset.UTC)

        assertEquals(listOf(t2), page.listens.map { it.listenedAt.epochMs })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listens filters by songId`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, song1) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val song1 = insertSong(album, title = "Song One")
            val song2 = insertSong(album, title = "Song Two")
            registerSong(song1, "Song One")
            registerSong(song2, "Song Two")
            insertListen(ms(2024, 1, 1), userId = u, songId = song1)
            insertListen(ms(2024, 1, 2), userId = u, songId = song2)
            u to song1
        }

        val page = service.listens(user, ListenFilter(songId = song1), 10, null, ZoneOffset.UTC)

        assertEquals(1, page.listens.size)
        assertEquals(song1.toString(), page.listens.single().song?.id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listens filters by artistId`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, artist1) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val artist1 = insertArtist("Artist One")
            val artist2 = insertArtist("Artist Two")
            val song1 = insertSong(album, title = "Song One")
            val song2 = insertSong(album, title = "Song Two")
            linkSongArtist(song1, artist1)
            linkSongArtist(song2, artist2)
            registerSong(song1, "Song One")
            registerSong(song2, "Song Two")
            insertListen(ms(2024, 1, 1), userId = u, songId = song1)
            insertListen(ms(2024, 1, 2), userId = u, songId = song2)
            u to artist1
        }

        val page = service.listens(user, ListenFilter(artistId = artist1), 10, null, ZoneOffset.UTC)

        assertEquals(1, page.listens.size)
        assertEquals("Song One", page.listens.single().song?.title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listens filters by albumId`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, album1) = transaction(database) {
            val u = insertUser()
            val album1 = insertAlbum("Album One")
            val album2 = insertAlbum("Album Two")
            val song1 = insertSong(album1, title = "Song One")
            val song2 = insertSong(album2, title = "Song Two")
            registerSong(song1, "Song One")
            registerSong(song2, "Song Two")
            insertListen(ms(2024, 1, 1), userId = u, songId = song1)
            insertListen(ms(2024, 1, 2), userId = u, songId = song2)
            u to album1
        }

        val page = service.listens(user, ListenFilter(albumId = album1), 10, null, ZoneOffset.UTC)

        assertEquals(1, page.listens.size)
        assertEquals("Song One", page.listens.single().song?.title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listens filters by source`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
            val song = insertSong(insertAlbum())
            registerSong(song, "Song")
            insertListen(ms(2024, 1, 1), userId = u, songId = song, source = ListenSource.LOCAL)
            insertListen(ms(2024, 1, 2), lbUserId = lb, songId = song, source = ListenSource.LISTENBRAINZ)
            u
        }

        val local = service.listens(user, ListenFilter(source = ListenSource.LOCAL), 10, null, ZoneOffset.UTC)
        val remote = service.listens(user, ListenFilter(source = ListenSource.LISTENBRAINZ), 10, null, ZoneOffset.UTC)

        assertEquals(1, local.listens.size)
        assertEquals("LOCAL", local.listens.single().source)
        assertEquals(1, remote.listens.size)
        assertEquals("LISTENBRAINZ", remote.listens.single().source)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listens filters by qualifiedOnly`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum(), durationMs = 240_000)
            registerSong(song, "Song")
            insertListen(ms(2024, 1, 1), userId = u, songId = song, playedMs = 30_000)
            insertListen(ms(2024, 1, 2), userId = u, songId = song, playedMs = null)
            u
        }

        val page = service.listens(user, ListenFilter(qualifiedOnly = true), 10, null, ZoneOffset.UTC)

        assertEquals(1, page.listens.size)
        assertTrue(page.listens.single().qualified)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listens keyset pagination walks all pages without overlap`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            registerSong(song, "Song")
            for (day in 1..5) {
                insertListen(ms(2024, 1, day), userId = u, songId = song)
            }
            u
        }

        val seen = LinkedHashSet<String>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            val page = service.listens(user, ListenFilter(), 2, cursor, ZoneOffset.UTC)
            pages++
            for (listen in page.listens) {
                assertTrue(seen.add(listen.id), "listen ${listen.id} appeared on more than one page")
            }
            if (!page.hasMore) {
                assertNull(page.nextCursor)
                break
            }
            cursor = page.nextCursor
        }

        assertEquals(5, seen.size)
        assertEquals(3, pages)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listens with an invalid cursor throws`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) { insertUser() }

        val error = runCatching { service.listens(user, ListenFilter(), 10, "garbage", ZoneOffset.UTC) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listens surfaces unmatched entries without a song`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mbid = UUID.randomUUID()
        val user = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
            insertListen(
                ms(2024, 1, 1),
                lbUserId = lb,
                trackName = "Track X",
                artistName = "Artist X",
                releaseName = "Album X",
                recordingMbid = mbid,
            )
            u
        }

        val page = service.listens(user, ListenFilter(), 10, null, ZoneOffset.UTC)

        val listen = page.listens.single()
        assertNull(listen.song)
        assertEquals("Track X", listen.unmatched?.trackName)
        assertEquals("Artist X", listen.unmatched?.artistName)
        assertEquals("Album X", listen.unmatched?.releaseName)
        assertEquals(mbid.toString(), listen.unmatched?.recordingMbid)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `summary counts listens and listened time using full duration when msPlayed is null`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum(), durationMs = 200_000)
            insertListen(ms(2024, 1, 1), userId = u, songId = song, playedMs = null)
            u
        }

        val result = service.summary(user, ListenFilter(), ZoneOffset.UTC)

        assertEquals(1L, result.listenCount)
        assertEquals(200_000L, result.listenedMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `summary counts unique songs artists and albums`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val album1 = insertAlbum("Album One")
            val album2 = insertAlbum("Album Two")
            val artist1 = insertArtist("Artist One")
            val artist2 = insertArtist("Artist Two")
            val song1 = insertSong(album1, title = "Song One")
            val song2 = insertSong(album2, title = "Song Two")
            linkSongArtist(song1, artist1)
            linkSongArtist(song2, artist2)
            insertListen(ms(2024, 1, 1), userId = u, songId = song1)
            insertListen(ms(2024, 1, 2), userId = u, songId = song2)
            u
        }

        val result = service.summary(user, ListenFilter(), ZoneOffset.UTC)

        assertEquals(2, result.uniqueSongs)
        assertEquals(2, result.uniqueArtists)
        assertEquals(2, result.uniqueAlbums)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `summary tracks first and last listen`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val t1 = ms(2024, 1, 1)
        val t2 = ms(2024, 1, 5)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            insertListen(t1, userId = u, songId = song)
            insertListen(t2, userId = u, songId = song)
            u
        }

        val result = service.summary(user, ListenFilter(), ZoneOffset.UTC)

        assertEquals(t1, result.firstListen?.epochMs)
        assertEquals(t2, result.lastListen?.epochMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `summary buckets hour of day and day of week in the requested timezone`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            insertListen(ms(2024, 1, 15, 23, 30), userId = u, songId = song)
            u
        }

        val result = service.summary(user, ListenFilter(), ZoneId.of("Europe/Berlin"))

        assertEquals(1L, result.hourOfDay[0])
        assertEquals(0L, result.hourOfDay[23])
        assertEquals(1L, result.dayOfWeek[1])
        assertEquals(0L, result.dayOfWeek[0])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `summary counts days with listens`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            insertListen(ms(2024, 1, 1), userId = u, songId = song)
            insertListen(ms(2024, 1, 2), userId = u, songId = song)
            u
        }

        val result = service.summary(user, ListenFilter(), ZoneOffset.UTC)

        assertEquals(2, result.daysWithListens)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `summary dedups listens of the same song within the dedup window`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            val t = ms(2024, 1, 1)
            insertListen(t, userId = u, songId = song)
            insertListen(t + 1000, userId = u, songId = song)
            u
        }

        val result = service.summary(user, ListenFilter(), ZoneOffset.UTC)

        assertEquals(1L, result.listenCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an own scrobble replaces its earlier ListenBrainz copy`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, localSong) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
            val album = insertAlbum()
            val lbSong = insertSong(album, title = "LB Song", durationMs = 229_133)
            val localSong = insertSong(album, title = "Local Song", durationMs = 229_133)
            val mbid = UUID.randomUUID()
            val t = ms(2026, 9, 10, 13, 49, 44)
            insertListen(t, lbUserId = lb, songId = lbSong, recordingMbid = mbid, isrcs = "US1111111111", playedMs = 229_133)
            insertListen(t + 83, userId = u, songId = localSong, recordingMbid = mbid, isrcs = "US1111111111", playedMs = 229_000)
            u to localSong
        }

        val summary = service.summary(user, ListenFilter(), ZoneOffset.UTC)
        val top = service.top(user, ListenFilter(), McpTopKind.SONGS, McpTopOrder.LISTEN_COUNT, 10, 0, ZoneOffset.UTC)

        assertEquals(1L, summary.listenCount)
        assertEquals(229_000L, summary.listenedMs)
        assertEquals(localSong.toString(), top.entries.single().id)
        assertEquals(229_000L, top.entries.single().listenedMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an own scrobble is kept over a later ListenBrainz copy`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, localSong) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
            val album = insertAlbum()
            val localSong = insertSong(album, title = "Local Song", durationMs = 157_020)
            val lbSong = insertSong(album, title = "LB Song", durationMs = 157_020)
            val mbid = UUID.randomUUID()
            val t = ms(2026, 9, 10, 14, 11, 11)
            insertListen(t, userId = u, songId = localSong, recordingMbid = mbid, playedMs = 146_000)
            insertListen(t + 1000, lbUserId = lb, songId = lbSong, recordingMbid = mbid, playedMs = 157_020)
            u to localSong
        }

        val top = service.top(user, ListenFilter(), McpTopKind.SONGS, McpTopOrder.LISTEN_COUNT, 10, 0, ZoneOffset.UTC)

        assertEquals(localSong.toString(), top.entries.single().id)
        assertEquals(146_000L, top.entries.single().listenedMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a ListenBrainz listen without an own twin is kept`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, lbSong) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
            val lbSong = insertSong(insertAlbum(), title = "LB Song", durationMs = 200_000)
            insertListen(ms(2026, 9, 10, 12), lbUserId = lb, songId = lbSong, playedMs = 200_000)
            u to lbSong
        }

        val top = service.top(user, ListenFilter(), McpTopKind.SONGS, McpTopOrder.LISTEN_COUNT, 10, 0, ZoneOffset.UTC)

        assertEquals(lbSong.toString(), top.entries.single().id)
        assertEquals(1L, top.entries.single().listenCount)
        assertEquals(200_000L, top.entries.single().listenedMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `two own scrobbles of the same play keep the first`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum(), durationMs = 240_000)
            val t = ms(2026, 9, 10, 12)
            insertListen(t, userId = u, songId = song, playedMs = 200_000)
            insertListen(t + 500, userId = u, songId = song, playedMs = 190_000)
            u
        }

        val summary = service.summary(user, ListenFilter(), ZoneOffset.UTC)

        assertEquals(1L, summary.listenCount)
        assertEquals(200_000L, summary.listenedMs)
        assertEquals(ms(2026, 9, 10, 12), summary.firstListen?.epochMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `top ranks songs differently by listen count and by listened time`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val songA = insertSong(album, title = "Song A")
            val songB = insertSong(album, title = "Song B")
            insertListen(ms(2024, 1, 1), userId = u, songId = songA, playedMs = 190_000)
            insertListen(ms(2024, 1, 2), userId = u, songId = songA, playedMs = 190_000)
            insertListen(ms(2024, 1, 3), userId = u, songId = songA, playedMs = 190_000)
            insertListen(ms(2024, 1, 4), userId = u, songId = songB, playedMs = 2_000_000)
            u
        }

        val byCount = service.top(user, ListenFilter(), McpTopKind.SONGS, McpTopOrder.LISTEN_COUNT, 10, 0, ZoneOffset.UTC)
        val byTime = service.top(user, ListenFilter(), McpTopKind.SONGS, McpTopOrder.LISTENED_MS, 10, 0, ZoneOffset.UTC)

        assertEquals("Song A", byCount.entries.first().name)
        assertEquals(3L, byCount.entries.first().listenCount)
        assertEquals("Song B", byTime.entries.first().name)
        assertEquals(2_000_000L, byTime.entries.first().listenedMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `top total rank and offset plus limit paging are consistent`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val song1 = insertSong(album, title = "Song One")
            val song2 = insertSong(album, title = "Song Two")
            val song3 = insertSong(album, title = "Song Three")
            insertListen(ms(2024, 1, 1), userId = u, songId = song1)
            insertListen(ms(2024, 1, 2), userId = u, songId = song1)
            insertListen(ms(2024, 1, 3), userId = u, songId = song1)
            insertListen(ms(2024, 1, 4), userId = u, songId = song2)
            insertListen(ms(2024, 1, 5), userId = u, songId = song2)
            insertListen(ms(2024, 1, 6), userId = u, songId = song3)
            u
        }

        val full = service.top(user, ListenFilter(), McpTopKind.SONGS, McpTopOrder.LISTEN_COUNT, 10, 0, ZoneOffset.UTC)
        val page1 = service.top(user, ListenFilter(), McpTopKind.SONGS, McpTopOrder.LISTEN_COUNT, 1, 0, ZoneOffset.UTC)
        val page2 = service.top(user, ListenFilter(), McpTopKind.SONGS, McpTopOrder.LISTEN_COUNT, 1, 1, ZoneOffset.UTC)

        assertEquals(3, full.total)
        assertEquals(listOf(1, 2, 3), full.entries.map { it.rank })
        assertEquals(full.entries[0].name, page1.entries.single().name)
        assertEquals(1, page1.entries.single().rank)
        assertEquals(full.entries[1].name, page2.entries.single().name)
        assertEquals(2, page2.entries.single().rank)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `top for artists credits every artist of a multi-artist song`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val artist1 = insertArtist("Artist One")
            val artist2 = insertArtist("Artist Two")
            val song = insertSong(album, title = "Collab")
            linkSongArtist(song, artist1)
            linkSongArtist(song, artist2)
            insertListen(ms(2024, 1, 1), userId = u, songId = song)
            insertListen(ms(2024, 1, 2), userId = u, songId = song)
            u
        }

        val result = service.top(user, ListenFilter(), McpTopKind.ARTISTS, McpTopOrder.LISTEN_COUNT, 10, 0, ZoneOffset.UTC)

        assertEquals(setOf("Artist One" to 2L, "Artist Two" to 2L), result.entries.map { it.name to it.listenCount }.toSet())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `top surfaces unmatched entries as unmatched with a null id`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
            insertListen(ms(2024, 1, 1), lbUserId = lb, trackName = "Unmatched Track", artistName = "Unmatched Artist")
            insertListen(ms(2024, 2, 1), lbUserId = lb, trackName = "Unmatched Track", artistName = "Unmatched Artist")
            u
        }

        val result = service.top(user, ListenFilter(), McpTopKind.SONGS, McpTopOrder.LISTEN_COUNT, 10, 0, ZoneOffset.UTC)

        val entry = result.entries.single()
        assertFalse(entry.matched)
        assertNull(entry.id)
        assertEquals("Unmatched Track", entry.name)
        assertEquals(2L, entry.listenCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `top artistId scope restricts results to that artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, artist1) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val artist1 = insertArtist("Artist One")
            val artist2 = insertArtist("Artist Two")
            val song1 = insertSong(album, title = "Song One")
            val song2 = insertSong(album, title = "Song Two")
            linkSongArtist(song1, artist1)
            linkSongArtist(song2, artist2)
            insertListen(ms(2024, 1, 1), userId = u, songId = song1)
            insertListen(ms(2024, 1, 2), userId = u, songId = song1)
            insertListen(ms(2024, 1, 3), userId = u, songId = song2)
            insertListen(ms(2024, 1, 4), userId = u, songId = song2)
            insertListen(ms(2024, 1, 5), userId = u, songId = song2)
            u to artist1
        }

        val result = service.top(user, ListenFilter(artistId = artist1), McpTopKind.SONGS, McpTopOrder.LISTEN_COUNT, 10, 0, ZoneOffset.UTC)

        assertEquals(1, result.total)
        assertEquals("Song One", result.entries.single().name)
        assertEquals(2L, result.entries.single().listenCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `timeline day buckets follow the requested timezone across a midnight boundary and include empty buckets`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val zone = ZoneId.of("Europe/Berlin")
        val from = ms(2024, 1, 15)
        val to = ms(2024, 1, 18)
        val user = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            insertListen(ms(2024, 1, 15, 23, 30), userId = u, songId = song)
            u
        }

        val result = service.timeline(user, ListenFilter(from = from, to = to), McpBucket.DAY, zone)

        assertEquals(4, result.buckets.size)
        assertEquals(0L, result.buckets[0].listenCount)
        assertEquals(1L, result.buckets[1].listenCount)
        assertEquals(0L, result.buckets[2].listenCount)
        assertEquals(0L, result.buckets[3].listenCount)
        val expectedBucketStart = ZonedDateTime.of(2024, 1, 16, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedBucketStart, result.buckets[1].bucketStart.epochMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `timeline throws when the bucket count would exceed the maximum`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) { insertUser() }
        val from = ms(2024, 1, 1)
        val to = from + 100L * 24 * 60 * 60 * 1000

        val error = runCatching {
            service.timeline(user, ListenFilter(from = from, to = to), McpBucket.HOUR, ZoneOffset.UTC)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `nowPlaying maps the current snapshot`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) { insertUser() }
        val song = UserSong(
            id = UUID.randomUUID(),
            title = "Now Playing Song",
            artists = emptyList(),
            album = null,
            duration = 200_000,
            explicit = false,
            path = "",
        )
        val startedAt = ms(2024, 1, 1)
        every { scrobbleService.currentNowPlaying(user) } returns NowPlayingSnapshot(
            song = song,
            startedAt = startedAt,
            positionMs = 5_000,
            playing = true,
        )

        val result = service.nowPlaying(user, ZoneOffset.UTC)

        assertEquals(song.id.toString(), result?.song?.id)
        assertEquals("Now Playing Song", result?.song?.title)
        assertEquals(startedAt, result?.startedAt?.epochMs)
        assertEquals(5_000L, result?.positionMs)
        assertTrue(result?.playing == true)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `nowPlaying returns null when nothing is playing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = transaction(database) { insertUser() }
        every { scrobbleService.currentNowPlaying(user) } returns null

        val result = service.nowPlaying(user, ZoneOffset.UTC)

        assertNull(result)
    }
}
