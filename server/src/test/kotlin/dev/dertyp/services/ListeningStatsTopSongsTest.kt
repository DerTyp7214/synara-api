package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ChangeNotifier
import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopOrder
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.db.*
import dev.dertyp.plugins.HookBus
import dev.dertyp.services.sync.ListenBrainzService
import dev.dertyp.testing.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.*

class ListeningStatsTopSongsTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: ListeningStatsService
    private val droppedIds = mutableSetOf<UUID>()
    private val requestedChunks = mutableListOf<List<UUID>>()

    private val nowUtc: ZonedDateTime = ZonedDateTime.of(2025, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC)
    private val nowMs = nowUtc.toInstant().toEpochMilli()

    private fun userSong(id: UUID) = UserSong(
        id = id,
        title = "Song",
        artists = emptyList(),
        album = null,
        duration = 0,
        explicit = false,
        path = "",
    )

    private fun setup(dialect: DbDialect) {
        droppedIds.clear()
        requestedChunks.clear()
        val songService = mockk<SongService>()
        coEvery { songService.byIds(any<List<UUID>>(), any<UUID>()) } answers {
            val ids = firstArg<List<UUID>>()
            requestedChunks.add(ids)
            ids.filter { it !in droppedIds }.map { userSong(it) }
        }
        startKoin {
            modules(module {
                single<HookBus> { mockk(relaxed = true) }
                single { songService }
                single { ListenService() }
                single { mockk<ChangeNotifier>(relaxed = true) }
                single { mockk<ListenBrainzService>(relaxed = true) }
            })
        }
        database = TestDatabase.connect(
            dialect, "listening_stats_top_songs_test",
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
            ListenSongRedirectTable,
        )
        service = ListeningStatsService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun at(daysAgo: Long, hour: Int = 12, minute: Int = 0): Long =
        nowUtc.minusDays(daysAgo).withHour(hour).withMinute(minute).toInstant().toEpochMilli()

    private fun epochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun insertSongWithId(albumId: UUID, songId: UUID, durationMs: Long = 240_000) {
        SongTable.insert {
            it[id] = songId
            it[title] = "Song"
            it[SongTable.albumId] = albumId
            it[fileSize] = 0
            it[duration] = durationMs
        }
    }

    private fun insertListen(
        at: Long,
        userId: UUID? = null,
        lbUserId: UUID? = null,
        songId: UUID? = null,
        trackName: String? = null,
        artistName: String? = null,
        playedMs: Long? = null,
    ) {
        ListenTable.insert {
            it[ListenTable.userId] = userId
            it[listenBrainzUserId] = lbUserId
            it[ListenTable.songId] = songId
            it[ListenTable.trackName] = trackName
            it[ListenTable.artistName] = artistName
            it[listenedAt] = at
            it[listenSource] = if (lbUserId != null) ListenSource.LISTENBRAINZ else ListenSource.LOCAL
            it[msPlayed] = playedMs
        }
    }

    private suspend fun topSongs(
        userId: UUID,
        range: StatsRange = StatsRange.ALL_TIME,
        timezone: String = "UTC",
        limit: Int = 10,
        order: TopOrder = TopOrder.LISTENED_MS,
    ): List<UUID> = service.topSongs(userId, range, timezone, limit, order, nowMs).map { it.id }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listened time order differs from listen count order`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, many, long) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val many = insertSong(album, durationMs = 240_000)
            val long = insertSong(album, durationMs = 1_200_000)
            insertListen(at(1), userId = u, songId = many, playedMs = 130_000)
            insertListen(at(2), userId = u, songId = many, playedMs = 130_000)
            insertListen(at(3), userId = u, songId = many, playedMs = 130_000)
            insertListen(at(4), userId = u, songId = long, playedMs = 1_200_000)
            Triple(u, many, long)
        }

        assertEquals(listOf(many, long), topSongs(user, order = TopOrder.LISTEN_COUNT))
        assertEquals(listOf(long, many), topSongs(user, order = TopOrder.LISTENED_MS))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a song with only short plays ranks by time and is absent by count`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, skipped, played) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val skipped = insertSong(album, durationMs = 240_000)
            val played = insertSong(album, durationMs = 240_000)
            insertListen(at(1), userId = u, songId = skipped, playedMs = 30_000)
            insertListen(at(2), userId = u, songId = skipped, playedMs = 30_000)
            insertListen(at(3), userId = u, songId = played, playedMs = 240_000)
            Triple(u, skipped, played)
        }

        assertEquals(listOf(played), topSongs(user, order = TopOrder.LISTEN_COUNT))
        assertEquals(listOf(played, skipped), topSongs(user, order = TopOrder.LISTENED_MS))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ranges decide which listens are ranked`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, old, current) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val old = insertSong(album, durationMs = 240_000)
            val current = insertSong(album, durationMs = 240_000)
            insertListen(epochMs(2024, 6, 1), userId = u, songId = old)
            insertListen(epochMs(2024, 6, 2), userId = u, songId = old)
            insertListen(epochMs(2025, 2, 1), userId = u, songId = current)
            Triple(u, old, current)
        }

        assertEquals(listOf(current), topSongs(user, StatsRange.YEAR))
        assertEquals(listOf(old), topSongs(user, StatsRange.LAST_YEAR))
        assertEquals(listOf(old, current), topSongs(user, StatsRange.ALL_TIME))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `year boundaries follow the requested timezone`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, song) = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum(), durationMs = 240_000)
            insertListen(epochMs(2024, 12, 31, hour = 23, minute = 30), userId = u, songId = song)
            u to song
        }

        assertEquals(emptyList<UUID>(), topSongs(user, StatsRange.YEAR, "UTC"))
        assertEquals(listOf(song), topSongs(user, StatsRange.LAST_YEAR, "UTC"))
        assertEquals(listOf(song), topSongs(user, StatsRange.YEAR, "Pacific/Auckland"))
        assertEquals(emptyList<UUID>(), topSongs(user, StatsRange.LAST_YEAR, "Pacific/Auckland"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unmatched listens take no slots`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, first, second) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            val album = insertAlbum()
            val first = insertSong(album, durationMs = 240_000)
            val second = insertSong(album, durationMs = 240_000)
            insertListen(at(1), userId = u, songId = first, playedMs = 240_000)
            insertListen(at(2), userId = u, songId = first, playedMs = 240_000)
            insertListen(at(3), userId = u, songId = second, playedMs = 200_000)
            for (i in 1..5) {
                insertListen(
                    at(10L + i),
                    lbUserId = lb,
                    trackName = "Unmatched $i",
                    artistName = "Nobody $i",
                    playedMs = 900_000,
                )
            }
            Triple(u, first, second)
        }

        assertEquals(listOf(first, second), topSongs(user, limit = 2, order = TopOrder.LISTENED_MS))
        assertEquals(listOf(first, second), topSongs(user, limit = 2, order = TopOrder.LISTEN_COUNT))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ids dropped by the song lookup are backfilled by the next ranked songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, songs) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val songs = (1..4).map { insertSong(album, durationMs = 240_000) }
            songs.forEachIndexed { index, song ->
                repeat(4 - index) { n -> insertListen(at(1L + index * 4 + n), userId = u, songId = song) }
            }
            u to songs
        }
        droppedIds.add(songs[0])

        val result = topSongs(user, limit = 2, order = TopOrder.LISTEN_COUNT)

        assertEquals(listOf(songs[1], songs[2]), result)
        assertEquals(2, result.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `equal songs are ordered by id ascending`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val ids = listOf(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
        )
        val user = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            ids.reversed().forEachIndexed { index, id ->
                insertSongWithId(album, id)
                insertListen(at(1L + index), userId = u, songId = id, playedMs = 240_000)
            }
            u
        }

        assertEquals(ids, topSongs(user, order = TopOrder.LISTEN_COUNT))
        assertEquals(ids, topSongs(user, order = TopOrder.LISTENED_MS))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a ListenBrainz copy of a local play is counted once`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, copied, twice) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            val album = insertAlbum()
            val copied = insertSong(album, durationMs = 300_000)
            val twice = insertSong(album, durationMs = 200_000)
            insertListen(at(1), userId = u, songId = copied)
            insertListen(at(1) + 1000, lbUserId = lb, songId = copied)
            insertListen(at(2), userId = u, songId = twice)
            insertListen(at(3), userId = u, songId = twice)
            Triple(u, copied, twice)
        }

        assertEquals(listOf(twice, copied), topSongs(user, order = TopOrder.LISTEN_COUNT))
        assertEquals(listOf(twice, copied), topSongs(user, order = TopOrder.LISTENED_MS))
    }

    @Test
    fun `the RPC adapter clamps the limit and passes the order through`() = runBlocking {
        val userId = UUID.randomUUID()
        val stats = mockk<ListeningStatsService>()
        val limits = mutableListOf<Int>()
        val orders = mutableListOf<TopOrder>()
        coEvery { stats.topSongs(any(), any(), any(), any(), any(), any()) } answers {
            limits.add(arg(3))
            orders.add(arg(4))
            emptyList()
        }
        val rpc = RpcListeningStatsService(User(userId, "listener"), stats)

        rpc.getTopSongs(StatsRange.ALL_TIME, "UTC", 0, TopOrder.LISTEN_COUNT)
        rpc.getTopSongs(StatsRange.ALL_TIME, "UTC", 10_000, TopOrder.LISTENED_MS)
        rpc.getTopSongs(StatsRange.ALL_TIME, "UTC", 42, TopOrder.LISTEN_COUNT)

        assertEquals(listOf(1, 500, 42), limits)
        assertEquals(listOf(TopOrder.LISTEN_COUNT, TopOrder.LISTENED_MS, TopOrder.LISTEN_COUNT), orders)
    }
}
