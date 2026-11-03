package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ChangeNotifier
import dev.dertyp.data.ArtistTopSong
import dev.dertyp.data.ArtistTopSongOrder
import dev.dertyp.data.UserSong
import dev.dertyp.db.*
import dev.dertyp.plugins.HookBus
import dev.dertyp.services.sync.ListenBrainzService
import dev.dertyp.services.sync.ListenBrainzTopRecording
import dev.dertyp.testing.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.*
import kotlin.time.Duration.Companion.days

class ArtistTopSongsServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: ArtistTopSongsService
    private val listenBrainzService = mockk<ListenBrainzService>()

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
        val songService = mockk<SongService>()
        coEvery { songService.byIds(any<List<UUID>>(), any<UUID>()) } answers {
            firstArg<List<UUID>>().map { userSong(it) }
        }
        startKoin {
            modules(module {
                single<HookBus> { mockk(relaxed = true) }
                single { songService }
                single { ListenService() }
                single { ListeningStatsService() }
                single { mockk<ChangeNotifier>(relaxed = true) }
                single { listenBrainzService }
            })
        }
        database = TestDatabase.connect(
            dialect, "artist_top_songs_test",
            UserTable,
            ImageTable,
            AlbumTable,
            ArtistTable,
            ArtistAliasTable,
            AlbumArtistTable,
            SongTable, SongVariantTable,
            SongArtistTable,
            MBAreaTable,
            MBArtistTable,
            MBRecordingTable,
            MBRecordingIsrcTable,
            SongMusicBrainzTable,
            ArtistMusicBrainzTable,
            MBArtistTopRecordingTable,
            MBArtistTopRecordingFetchTable,
            ListenBrainzUserTable,
            UserListenBrainzLinkTable,
            ListenTable,
            ListenShareTable,
            ListenLinkTable,
            ListenSongRedirectTable,
        )
        service = ArtistTopSongsService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertMbArtist(artistId: UUID): UUID {
        val mbid = UUID.randomUUID()
        MBArtistTable.insert {
            it[id] = mbid
            it[name] = "Artist"
            it[sortName] = "Artist"
        }
        ArtistMusicBrainzTable.insert {
            it[ArtistMusicBrainzTable.artistId] = artistId
            it[musicBrainzId] = mbid
        }
        return mbid
    }

    private fun linkRecording(songId: UUID): UUID {
        val recording = UUID.randomUUID()
        MBRecordingTable.insert {
            it[id] = recording
            it[title] = "Song"
        }
        SongMusicBrainzTable.insert {
            it[SongMusicBrainzTable.songId] = songId
            it[musicBrainzId] = recording
        }
        return recording
    }

    private fun recordingWithIsrc(isrc: String): UUID {
        val recording = UUID.randomUUID()
        MBRecordingTable.insert {
            it[id] = recording
            it[title] = "Song"
        }
        MBRecordingIsrcTable.insert {
            it[recordingId] = recording
            it[MBRecordingIsrcTable.isrc] = isrc
        }
        return recording
    }

    private fun insertListen(songId: UUID, userId: UUID? = null, lbUserId: UUID? = null, at: Long) {
        ListenTable.insert {
            it[ListenTable.userId] = userId
            it[listenBrainzUserId] = lbUserId
            it[ListenTable.songId] = songId
            it[listenedAt] = at
            it[listenSource] = if (lbUserId != null) ListenSource.LISTENBRAINZ else ListenSource.LOCAL
        }
    }

    private fun cacheRecording(artistMbid: UUID, recording: UUID, listens: Long, users: Long) {
        MBArtistTopRecordingTable.insert {
            it[artistId] = artistMbid
            it[recordingMbid] = recording
            it[listenCount] = listens
            it[userCount] = users
        }
    }

    private fun markFetched(artistMbid: UUID, at: Long) {
        MBArtistTopRecordingFetchTable.insert {
            it[artistId] = artistMbid
            it[fetchedAt] = at
        }
    }

    private fun top(recording: UUID?, listens: Long, users: Long) =
        ListenBrainzTopRecording(recording?.toString(), listens, users)

    private fun List<ArtistTopSong>.summary() =
        map { Triple(it.song.id, it.globalListenCount, it.ownListenCount) }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a cache miss fetches, stores and matches by recording and by ISRC`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        data class Fixture(val user: UUID, val artist: UUID, val mbid: UUID, val byMbid: UUID, val byIsrc: UUID, val recordings: List<UUID>)
        val f = transaction(database) {
            val user = insertUser()
            val artist = insertArtist()
            val mbid = insertMbArtist(artist)
            val album = insertAlbum()
            val byMbid = insertSong(album)
            val byIsrc = insertSong(album, isrc = "USABC1234567")
            val recordingA = linkRecording(byMbid)
            val recordingB = recordingWithIsrc("USABC1234567")
            Fixture(user, artist, mbid, byMbid, byIsrc, listOf(recordingA, recordingB, UUID.randomUUID()))
        }
        coEvery { listenBrainzService.topRecordingsForArtist(f.mbid) } returns listOf(
            top(f.recordings[0], 500, 50),
            top(f.recordings[1], 900, 90),
            top(f.recordings[2], 2000, 200),
            top(null, 3000, 300),
        )

        val result = service.topSongs(f.user, f.artist, 10, ArtistTopSongOrder.GLOBAL_LISTENS)

        assertEquals(listOf(Triple(f.byIsrc, 900L, 0L), Triple(f.byMbid, 500L, 0L)), result.summary())
        assertEquals(listOf(90L, 50L), result.map { it.globalUserCount })
        coVerify(exactly = 1) { listenBrainzService.topRecordingsForArtist(f.mbid) }
        transaction(database) {
            val stored = MBArtistTopRecordingTable.selectAll()
                .where { MBArtistTopRecordingTable.artistId eq f.mbid }
                .associate { it[MBArtistTopRecordingTable.recordingMbid] to it[MBArtistTopRecordingTable.listenCount] }
            assertEquals(mapOf(f.recordings[0] to 500L, f.recordings[1] to 900L, f.recordings[2] to 2000L), stored)
            val fetchedAt = MBArtistTopRecordingFetchTable.selectAll()
                .where { MBArtistTopRecordingFetchTable.artistId eq f.mbid }
                .single()[MBArtistTopRecordingFetchTable.fetchedAt]
            assertTrue(System.currentTimeMillis() - fetchedAt < 1.days.inWholeMilliseconds)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a fresh cache is served without fetching, also when it is empty`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        data class Fixture(val user: UUID, val artist: UUID, val emptyArtist: UUID, val song: UUID)
        val f = transaction(database) {
            val user = insertUser()
            val artist = insertArtist()
            val mbid = insertMbArtist(artist)
            val emptyArtist = insertArtist("Empty")
            val emptyMbid = insertMbArtist(emptyArtist)
            val song = insertSong(insertAlbum())
            cacheRecording(mbid, linkRecording(song), 700, 70)
            markFetched(mbid, System.currentTimeMillis() - 1.days.inWholeMilliseconds)
            markFetched(emptyMbid, System.currentTimeMillis())
            Fixture(user, artist, emptyArtist, song)
        }

        val result = service.topSongs(f.user, f.artist, 10, ArtistTopSongOrder.GLOBAL_LISTENS)
        val empty = service.topSongs(f.user, f.emptyArtist, 10, ArtistTopSongOrder.GLOBAL_LISTENS)

        assertEquals(listOf(Triple(f.song, 700L, 0L)), result.summary())
        assertEquals(emptyList<ArtistTopSong>(), empty)
        coVerify(exactly = 0) { listenBrainzService.topRecordingsForArtist(any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a stale cache is fetched again and replaced`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        data class Fixture(val user: UUID, val artist: UUID, val mbid: UUID, val oldSong: UUID, val newSong: UUID, val newRecording: UUID)
        val f = transaction(database) {
            val user = insertUser()
            val artist = insertArtist()
            val mbid = insertMbArtist(artist)
            val album = insertAlbum()
            val oldSong = insertSong(album)
            val newSong = insertSong(album)
            cacheRecording(mbid, linkRecording(oldSong), 700, 70)
            val newRecording = linkRecording(newSong)
            markFetched(mbid, System.currentTimeMillis() - 8.days.inWholeMilliseconds)
            Fixture(user, artist, mbid, oldSong, newSong, newRecording)
        }
        coEvery { listenBrainzService.topRecordingsForArtist(f.mbid) } returns listOf(top(f.newRecording, 300, 30))

        val result = service.topSongs(f.user, f.artist, 10, ArtistTopSongOrder.GLOBAL_LISTENS)

        assertEquals(listOf(Triple(f.newSong, 300L, 0L)), result.summary())
        coVerify(exactly = 1) { listenBrainzService.topRecordingsForArtist(f.mbid) }
        transaction(database) {
            assertEquals(
                listOf(f.newRecording),
                MBArtistTopRecordingTable.selectAll()
                    .where { MBArtistTopRecordingTable.artistId eq f.mbid }
                    .map { it[MBArtistTopRecordingTable.recordingMbid] },
            )
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a failed fetch serves the stale cache`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        data class Fixture(val user: UUID, val artist: UUID, val mbid: UUID, val song: UUID, val staleAt: Long)
        val staleAt = System.currentTimeMillis() - 8.days.inWholeMilliseconds
        val f = transaction(database) {
            val user = insertUser()
            val artist = insertArtist()
            val mbid = insertMbArtist(artist)
            val song = insertSong(insertAlbum())
            cacheRecording(mbid, linkRecording(song), 700, 70)
            markFetched(mbid, staleAt)
            Fixture(user, artist, mbid, song, staleAt)
        }
        coEvery { listenBrainzService.topRecordingsForArtist(f.mbid) } returns null

        val result = service.topSongs(f.user, f.artist, 10, ArtistTopSongOrder.GLOBAL_LISTENS)

        assertEquals(listOf(Triple(f.song, 700L, 0L)), result.summary())
        assertEquals(listOf(70L), result.map { it.globalUserCount })
        coVerify(exactly = 1) { listenBrainzService.topRecordingsForArtist(f.mbid) }
        transaction(database) {
            assertEquals(
                f.staleAt,
                MBArtistTopRecordingFetchTable.selectAll()
                    .where { MBArtistTopRecordingFetchTable.artistId eq f.mbid }
                    .single()[MBArtistTopRecordingFetchTable.fetchedAt],
            )
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `own listens of the artist's songs are merged and both orders rank the union`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        data class Fixture(
            val user: UUID,
            val artist: UUID,
            val popular: UUID,
            val niche: UUID,
            val albumSong: UUID,
            val otherArtistSong: UUID,
        )
        val now = System.currentTimeMillis()
        val f = transaction(database) {
            val user = insertUser()
            val partner = insertUser()
            linkListenShare(user, partner)
            val artist = insertArtist()
            val mbid = insertMbArtist(artist)
            val otherArtist = insertArtist("Other")
            val album = insertAlbum()
            val artistAlbum = insertAlbum("Artist album")
            AlbumArtistTable.insert {
                it[albumId] = artistAlbum
                it[artistId] = artist
            }
            val popular = insertSong(album).also { linkSongArtist(it, artist) }
            val niche = insertSong(album).also { linkSongArtist(it, artist) }
            val albumSong = insertSong(artistAlbum)
            val otherArtistSong = insertSong(album).also { linkSongArtist(it, otherArtist) }
            cacheRecording(mbid, linkRecording(popular), 1000, 100)
            markFetched(mbid, now)

            insertListen(popular, userId = user, at = now - 100_000)
            insertListen(niche, userId = user, at = now - 200_000)
            insertListen(niche, userId = user, at = now - 300_000)
            insertListen(niche, userId = partner, at = now - 400_000)
            insertListen(albumSong, userId = partner, at = now - 500_000)
            insertListen(otherArtistSong, userId = user, at = now - 600_000)
            Fixture(user, artist, popular, niche, albumSong, otherArtistSong)
        }

        val byGlobal = service.topSongs(f.user, f.artist, 10, ArtistTopSongOrder.GLOBAL_LISTENS)
        val byOwn = service.topSongs(f.user, f.artist, 10, ArtistTopSongOrder.OWN_LISTENS)
        val limited = service.topSongs(f.user, f.artist, 2, ArtistTopSongOrder.OWN_LISTENS)

        assertEquals(
            listOf(Triple(f.popular, 1000L, 1L), Triple(f.niche, null, 3L), Triple(f.albumSong, null, 1L)),
            byGlobal.summary(),
        )
        assertEquals(
            listOf(Triple(f.niche, null, 3L), Triple(f.popular, 1000L, 1L), Triple(f.albumSong, null, 1L)),
            byOwn.summary(),
        )
        assertEquals(listOf(f.niche, f.popular), limited.map { it.song.id })
        assertEquals(listOf(100L, null, null), byGlobal.map { it.globalUserCount })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an artist without MBID lists only songs with own listens`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val now = System.currentTimeMillis()
        val (user, artist, listened) = transaction(database) {
            val user = insertUser()
            val artist = insertArtist()
            val album = insertAlbum()
            val listened = insertSong(album).also { linkSongArtist(it, artist) }
            insertSong(album).also { linkSongArtist(it, artist) }
            insertListen(listened, userId = user, at = now - 100_000)
            Triple(user, artist, listened)
        }

        val result = service.topSongs(user, artist, 10, ArtistTopSongOrder.GLOBAL_LISTENS)

        assertEquals(listOf(Triple(listened, null, 1L)), result.summary())
        assertEquals(listOf<Long?>(null), result.map { it.globalUserCount })
        coVerify(exactly = 0) { listenBrainzService.topRecordingsForArtist(any()) }
    }
}
