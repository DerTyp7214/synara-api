package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ChangeNotifier
import dev.dertyp.data.ChangeTopic
import dev.dertyp.data.ListenGroupSource
import dev.dertyp.data.LinkedListenGroupKey
import dev.dertyp.data.UserSong
import dev.dertyp.db.*
import dev.dertyp.plugins.HookBus
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.LinkResolverService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.testing.*
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.*

class ListenServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: ListenService
    private lateinit var songService: SongService
    private val changeNotifier = mockk<ChangeNotifier>(relaxed = true)

    private fun setup(dialect: DbDialect) {
        songService = mockk()
        coEvery { songService.byIds(any(), any<UUID>()) } answers {
            firstArg<List<UUID>>().map { songStub(it) }
        }
        startKoin {
            modules(module {
                single<HookBus> { mockk(relaxed = true) }
                single { songService }
                single { changeNotifier }
            })
        }
        database = TestDatabase.connect(
            dialect, "listen_test",
            UserTable,
            ImageTable,
            AlbumTable,
            ArtistTable,
            ArtistAliasTable,
            SongTable, SongVariantTable,
            SongArtistTable,
            UserSongTable,
            MBArtistTable,
            MBRecordingTable,
            MBReleaseGroupTable,
            MBReleaseTable,
            MBRecordingIsrcTable,
            SongMusicBrainzTable,
            AlbumMusicBrainzTable,
            ArtistMusicBrainzTable,
            ListenBrainzUserTable,
            UserListenBrainzLinkTable,
            ListenTable,
            ListenShareTable,
            ListenLinkTable,
            ListenSongRedirectTable,
        )
        service = ListenService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private val libraryTables = arrayOf(
        UserTable,
        ImageTable,
        ImageMetadataTable,
        AnimatedImageTable,
        ArtistTable,
        AlbumTable,
        SongTable,
        SongVariantTable,
        SongArtistTable,
        SongMusicBrainzTable,
        SongAudioDataTable,
        GenreTable,
        AlbumMusicBrainzTable,
        ArtistMusicBrainzTable,
        ArtistAliasTable,
        ArtistMemberTable,
        AlbumArtistTable,
        PlaylistTable,
        UserSongTable,
        TimecodeTagTable,
        UserPlaylistTable,
        SongGenreTable,
        ArtistGenreTable,
        AlbumGenreTable,
        PlaylistSongTable,
        UserPlaylistSongTable,
        UserPlaylistShareTable,
        SyncedLyricsTable,
        RecentReleaseTable,
        FollowedArtistTable,
        TranscodedSongTable,
        CustomMigrationTable,
        ScheduledTaskLogTable,
        ArtistSplitAliasTable,
        SyncServiceTable,
        SongProviderTable,
        AlbumProviderTable,
        CollectionTable,
        CollectionSongTable,
        CollectionAlbumTable,
        CollectionArtistTable,
        CollectionPlaylistTable,
        ListenBrainzUserTable,
        UserListenBrainzLinkTable,
        ListenTable,
        ListenShareTable,
        ListenLinkTable,
        ListenSongRedirectTable,
        *allMusicBrainzTables,
    )

    private fun setupWithLibrary(dialect: DbDialect) {
        val storageService = mockk<StorageService>(relaxed = true)
        every { storageService.albumsPath } returns null
        startKoin {
            modules(module {
                single<HookBus> { HookService() }
                single { changeNotifier }
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<CachedMusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { mockk<MetadataFetchingService>(relaxed = true) }
                single { mockk<GenreService>(relaxed = true) }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<LibraryMergeService>(relaxed = true) }
                single { mockk<LinkResolverService>(relaxed = true) }
                single { storageService }
                single { SongService() }
                single { ArtistService() }
                single { AlbumService() }
            })
        }
        database = TestDatabase.connect(dialect, "listen_library_test", *libraryTables)
        service = ListenService()
    }

    private fun songStub(id: UUID): UserSong {
        val song = mockk<UserSong>(relaxed = true)
        io.mockk.every { song.id } returns id
        return song
    }

    private fun insertLocal(userId: UUID, songId: UUID, at: Long, isrcs: String? = null, recordingMbid: UUID? = null) {
        ListenTable.insert {
            it[ListenTable.userId] = userId
            it[ListenTable.songId] = songId
            it[listenedAt] = at
            it[listenSource] = ListenSource.LOCAL
            it[ListenTable.isrcs] = isrcs
            it[ListenTable.recordingMbid] = recordingMbid
        }
    }

    private fun insertLb(lbUserId: UUID, songId: UUID, at: Long, isrcs: String? = null, recordingMbid: UUID? = null) {
        ListenTable.insert {
            it[listenBrainzUserId] = lbUserId
            it[ListenTable.songId] = songId
            it[listenedAt] = at
            it[listenSource] = ListenSource.LISTENBRAINZ
            it[ListenTable.isrcs] = isrcs
            it[ListenTable.recordingMbid] = recordingMbid
        }
    }

    private fun insertUnmatchedLb(
        lbUserId: UUID,
        at: Long,
        recordingMbid: UUID? = null,
        recordingMsid: UUID? = null,
        trackName: String? = null,
    ) {
        ListenTable.insert {
            it[listenBrainzUserId] = lbUserId
            it[ListenTable.recordingMbid] = recordingMbid
            it[ListenTable.recordingMsid] = recordingMsid
            it[ListenTable.trackName] = trackName
            it[listenedAt] = at
            it[listenSource] = ListenSource.LISTENBRAINZ
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `linkUnmatched by MBID links matching unmatched listens and stores an override`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val mbid = UUID.randomUUID()
            val otherMbid = UUID.randomUUID()
            val msid = UUID.randomUUID()
            val (user, song) = transaction(database) {
                val u = insertUser()
                val lb = insertLbUser()
                linkListenBrainzUser(u, lb)
                val song = insertSong(insertAlbum())
                insertUnmatchedLb(lb, 100, recordingMbid = mbid, recordingMsid = msid)
                insertUnmatchedLb(lb, 200, recordingMbid = mbid)
                insertUnmatchedLb(lb, 300, recordingMbid = otherMbid)
                u to song
            }

            val result = service.linkUnmatched(user, song, null, mbid)

            assertEquals(2, result.linkedListens)
            assertEquals(listOf(msid), result.recordingMsids)
            transaction(database) {
                val linked = ListenTable.selectAll().where { ListenTable.recordingMbid eq mbid }
                    .map { it[ListenTable.songId]?.value }
                assertEquals(listOf(song, song), linked)
                val untouched = ListenTable.selectAll().where { ListenTable.recordingMbid eq otherMbid }.single()
                assertEquals(null, untouched[ListenTable.songId])
                val override = ListenLinkTable.selectAll().single()
                assertEquals(user, override[ListenLinkTable.userId].value)
                assertEquals(song, override[ListenLinkTable.songId].value)
                assertEquals(mbid, override[ListenLinkTable.recordingMbid])
                assertEquals(null, override[ListenLinkTable.recordingMsid])
            }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `linkUnmatched by MSID expands to the group's MBID`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mbid = UUID.randomUUID()
        val msid = UUID.randomUUID()
        val (user, song) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            val song = insertSong(insertAlbum())
            insertUnmatchedLb(lb, 100, recordingMbid = mbid, recordingMsid = msid)
            insertUnmatchedLb(lb, 200, recordingMbid = mbid)
            insertUnmatchedLb(lb, 300, recordingMsid = UUID.randomUUID())
            u to song
        }

        val result = service.linkUnmatched(user, song, msid, null)

        assertEquals(2, result.linkedListens)
        assertEquals(listOf(msid), result.recordingMsids)
        transaction(database) {
            val override = ListenLinkTable.selectAll().single()
            assertEquals(mbid, override[ListenLinkTable.recordingMbid])
            assertEquals(msid, override[ListenLinkTable.recordingMsid])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `linkUnmatched leaves other users' listens alone`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mbid = UUID.randomUUID()
        val (user, song, otherLb) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            val other = insertUser()
            val otherLb = insertLbUser()
            linkListenBrainzUser(other, otherLb)
            val song = insertSong(insertAlbum())
            insertUnmatchedLb(lb, 100, recordingMbid = mbid)
            insertUnmatchedLb(otherLb, 200, recordingMbid = mbid)
            Triple(u, song, otherLb)
        }

        val result = service.linkUnmatched(user, song, null, mbid)

        assertEquals(1, result.linkedListens)
        transaction(database) {
            val otherRow = ListenTable.selectAll().where { ListenTable.listenBrainzUserId eq otherLb }.single()
            assertEquals(null, otherRow[ListenTable.songId])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `re-linking moves previously linked listens and replaces the override`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mbid = UUID.randomUUID()
        val (user, song1, song2, song3) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            val album = insertAlbum()
            val song1 = insertSong(album)
            val song2 = insertSong(album)
            val song3 = insertSong(album)
            insertUnmatchedLb(lb, 100, recordingMbid = mbid)
            insertLb(lb, song3, 200, recordingMbid = mbid)
            Quad(u, song1, song2, song3)
        }

        assertEquals(1, service.linkUnmatched(user, song1, null, mbid).linkedListens)
        assertEquals(1, service.linkUnmatched(user, song2, null, mbid).linkedListens)

        transaction(database) {
            val songs = ListenTable.selectAll().where { ListenTable.recordingMbid eq mbid }
                .orderBy(ListenTable.listenedAt).map { it[ListenTable.songId]?.value }
            assertEquals(listOf(song2, song3), songs)
            val override = ListenLinkTable.selectAll().single()
            assertEquals(song2, override[ListenLinkTable.songId].value)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `linkUnmatched rejects missing identity and unknown songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, song) = transaction(database) {
            val u = insertUser()
            u to insertSong(insertAlbum())
        }

        assertThrows<IllegalArgumentException> { service.linkUnmatched(user, song, null, null) }
        assertThrows<IllegalArgumentException> {
            service.linkUnmatched(user, UUID.randomUUID(), null, UUID.randomUUID())
        }
        Unit
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `local listens are returned newest first`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, s1, s2, s3) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val s1 = insertSong(album)
            val s2 = insertSong(album)
            val s3 = insertSong(album)
            insertLocal(u, s1, 100)
            insertLocal(u, s2, 200)
            insertLocal(u, s3, 300)
            Quad(u, s1, s2, s3)
        }

        val result = service.recentListens(user, 10)

        assertEquals(listOf(s3, s2, s1), result.map { it.song.id })
        assertEquals(listOf(300L, 200L, 100L), result.map { it.listenedAt })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listens from ListenBrainz and local sources are merged newest first`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, s1, s2, s3) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            val album = insertAlbum()
            val s1 = insertSong(album)
            val s2 = insertSong(album)
            val s3 = insertSong(album)
            insertLocal(u, s1, 100)
            insertLb(lb, s2, 250)
            insertLocal(u, s3, 400)
            Quad(u, s1, s2, s3)
        }

        val result = service.recentListens(user, 10)

        assertEquals(listOf(s3, s2, s1), result.map { it.song.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the same play captured by two scrobblers is collapsed to one`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, song) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            val song = insertSong(insertAlbum())
            insertLb(lb, song, 10_000)
            insertLocal(u, song, 11_000)
            u to song
        }

        val result = service.recentListens(user, 10)

        assertEquals(listOf(song), result.map { it.song.id })
        assertEquals(11_000L, result.single().listenedAt)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `repeated plays outside the dedup window are kept separate`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, song) = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            insertLocal(u, song, 10_000)
            insertLocal(u, song, 13_000)
            u to song
        }

        val result = service.recentListens(user, 10)

        assertEquals(listOf(song, song), result.map { it.song.id })
        assertEquals(listOf(13_000L, 10_000L), result.map { it.listenedAt })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `plays exactly on the dedup window boundary are collapsed`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, _) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            val song = insertSong(insertAlbum())
            insertLb(lb, song, 10_000)
            insertLocal(u, song, 10_000 + ListenTable.DEDUP_WINDOW_MS)
            u to song
        }

        val result = service.recentListens(user, 10)

        assertEquals(1, result.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `only the requesting user's listens are returned`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, _) = transaction(database) {
            val u = insertUser()
            val other = insertUser()
            val song = insertSong(insertAlbum())
            insertLocal(other, song, 100)
            u to song
        }

        assertEquals(emptyList<UUID>(), service.recentListens(user, 10).map { it.song.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `same ISRC with different songIds within the window is collapsed`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, s2) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            val album = insertAlbum()
            val s1 = insertSong(album)
            val s2 = insertSong(album)
            insertLb(lb, s1, 10_000, isrcs = "US1111111111")
            insertLocal(u, s2, 10_500, isrcs = "US1111111111")
            u to s2
        }

        val result = service.recentListens(user, 10)

        assertEquals(listOf(s2), result.map { it.song.id })
        assertEquals(10_500L, result.single().listenedAt)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listens that share one of several ISRCs are collapsed`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, s2) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            val album = insertAlbum()
            val s1 = insertSong(album)
            val s2 = insertSong(album)
            insertLb(lb, s1, 10_000, isrcs = "US1111111111,US2222222222")
            insertLocal(u, s2, 10_500, isrcs = "US2222222222")
            u to s2
        }

        val result = service.recentListens(user, 10)

        assertEquals(listOf(s2), result.map { it.song.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `same recording MBID with different songIds within the window is collapsed`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, s2) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            val album = insertAlbum()
            val s1 = insertSong(album)
            val s2 = insertSong(album)
            val mbid = UUID.randomUUID()
            insertLb(lb, s1, 10_000, recordingMbid = mbid)
            insertLocal(u, s2, 10_500, recordingMbid = mbid)
            u to s2
        }

        val result = service.recentListens(user, 10)

        assertEquals(listOf(s2), result.map { it.song.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an own scrobble is kept over a later ListenBrainz copy`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, s1) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            val album = insertAlbum()
            val s1 = insertSong(album)
            val s2 = insertSong(album)
            val mbid = UUID.randomUUID()
            insertLocal(u, s1, 10_000, recordingMbid = mbid)
            insertLb(lb, s2, 10_500, recordingMbid = mbid)
            u to s1
        }

        val result = service.recentListens(user, 10)

        assertEquals(listOf(s1), result.map { it.song.id })
        assertEquals(10_000L, result.single().listenedAt)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `same ISRC outside the window is kept separate`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, s1, s2) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val s1 = insertSong(album)
            val s2 = insertSong(album)
            insertLocal(u, s1, 10_000, isrcs = "US2222222222")
            insertLocal(u, s2, 13_000, isrcs = "US2222222222")
            Triple(u, s1, s2)
        }

        val result = service.recentListens(user, 10)

        assertEquals(listOf(s2, s1), result.map { it.song.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ingestLocal enriches the listen with library metadata`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, song) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum("My Album")
            val song = insertSong(album, title = "My Track", isrc = "us1234567890")
            linkSongArtist(song, insertArtist("My Artist"))
            u to song
        }

        service.ingestLocal(user, song, 500, 250)

        transaction(database) {
            val row = ListenTable.selectAll().where { ListenTable.songId eq song }.single()
            assertEquals("US1234567890", row[ListenTable.isrcs])
            assertEquals("My Track", row[ListenTable.trackName])
            assertEquals("My Artist", row[ListenTable.artistName])
            assertEquals("My Album", row[ListenTable.releaseName])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ingestLocal stores a LOCAL listen owned by the user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, song) = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            u to song
        }

        service.ingestLocal(user, song, 500, 250)

        transaction(database) {
            val row = ListenTable.selectAll().where { ListenTable.songId eq song }.single()
            assertEquals(user, row[ListenTable.userId]?.value)
            assertEquals(ListenSource.LOCAL, row[ListenTable.listenSource])
            assertEquals(500L, row[ListenTable.listenedAt])
            assertEquals(250L, row[ListenTable.msPlayed])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentSeedWeights weights songs by played fraction`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, full, partial, skipped) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val full = insertSong(album)
            val partial = insertSong(album)
            val skipped = insertSong(album)
            SongTable.update({ SongTable.id inList listOf(partial, skipped) }) { it[duration] = 200_000L }
            insertLocal(u, full, 1_000_000)
            insertLocal(u, full, 2_000_000)
            insertLocalPlayed(u, partial, 3_000_000, 50_000)
            insertLocalPlayed(u, partial, 4_000_000, 100_000)
            insertLocalPlayed(u, skipped, 5_000_000, 0)
            insertLocalPlayed(u, skipped, 100, 200_000)
            Quad(u, full, partial, skipped)
        }

        val weights = service.recentSeedWeights(user, 500)

        assertEquals(setOf(full, partial), weights.keys)
        assertEquals(2f, weights[full])
        assertEquals(0.75f, weights[partial])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentSeedWeights falls back to favourites`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) { SchemaUtils.create(UserSongTable) }
        val (user, favourite) = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            UserSongTable.insert {
                it[userId] = u
                it[songId] = song
                it[isFavourite] = true
            }
            u to song
        }

        assertEquals(mapOf(favourite to 1f), service.recentSeedWeights(user, 0))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentSeedWeights without listens adds the super like bonus to a super liked favourite`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            transaction(database) { SchemaUtils.create(UserSongTable) }
            val (user, favourite, superLiked) = transaction(database) {
                val u = insertUser()
                val album = insertAlbum()
                val favourite = insertSong(album)
                val superLiked = insertSong(album)
                UserSongTable.insert {
                    it[userId] = u
                    it[songId] = favourite
                    it[isFavourite] = true
                }
                UserSongTable.insert {
                    it[userId] = u
                    it[songId] = superLiked
                    it[isFavourite] = true
                    it[superLikedAt] = 1_000L
                }
                Triple(u, favourite, superLiked)
            }

            val weights = service.recentSeedWeights(user, 0)

            assertEquals(setOf(favourite, superLiked), weights.keys)
            assertEquals(1f, weights[favourite])
            assertEquals(1f + ListenService.SUPER_LIKE_SEED_BONUS, weights[superLiked])
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentSeedWeights adds the super like bonus on top of listen weights`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) { SchemaUtils.create(UserSongTable) }
        val (user, listened, superLikedOnly, superLikedAndListened) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val listened = insertSong(album)
            val superLikedOnly = insertSong(album)
            val superLikedAndListened = insertSong(album)
            insertLocal(u, listened, 1_000_000)
            insertLocal(u, superLikedAndListened, 1_000_000)
            UserSongTable.insert {
                it[userId] = u
                it[songId] = superLikedOnly
                it[isFavourite] = true
                it[superLikedAt] = 1_000L
            }
            UserSongTable.insert {
                it[userId] = u
                it[songId] = superLikedAndListened
                it[isFavourite] = true
                it[superLikedAt] = 2_000L
            }
            Quad(u, listened, superLikedOnly, superLikedAndListened)
        }

        val weights = service.recentSeedWeights(user, 500)

        assertEquals(setOf(listened, superLikedOnly, superLikedAndListened), weights.keys)
        assertEquals(1f, weights[listened])
        assertEquals(ListenService.SUPER_LIKE_SEED_BONUS, weights[superLikedOnly])
        assertEquals(1f + ListenService.SUPER_LIKE_SEED_BONUS, weights[superLikedAndListened])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentArtists returns distinct artists ordered by their latest listen`(dialect: DbDialect) = runBlocking {
        setupWithLibrary(dialect)
        val (user, artistOld, artistNew) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val songOld = insertSong(album)
            val songNew = insertSong(album)
            val artistOld = insertArtist("Old Artist")
            val artistNew = insertArtist("New Artist")
            linkSongArtist(songOld, artistOld)
            linkSongArtist(songNew, artistNew)
            insertLocal(u, songOld, 1_000)
            insertLocal(u, songOld, 2_000)
            insertLocal(u, songNew, 5_000)
            Triple(u, artistOld, artistNew)
        }

        val result = service.recentArtists(user, 10)

        assertEquals(listOf(artistNew, artistOld), result.map { it.artist.id })
        assertEquals(listOf(5_000L, 2_000L), result.map { it.lastListenedAt })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentArtists respects the limit`(dialect: DbDialect) = runBlocking {
        setupWithLibrary(dialect)
        val (user, top, mid) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val songs = (1..3).map { insertSong(album) }
            val artists = (1..3).map { insertArtist("Artist $it") }
            songs.zip(artists).forEach { (s, a) -> linkSongArtist(s, a) }
            insertLocal(u, songs[0], 1_000)
            insertLocal(u, songs[1], 2_000)
            insertLocal(u, songs[2], 3_000)
            Triple(u, artists[2], artists[1])
        }

        val result = service.recentArtists(user, 2)

        assertEquals(listOf(top, mid), result.map { it.artist.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentArtists excludes other users' listens`(dialect: DbDialect) = runBlocking {
        setupWithLibrary(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val other = insertUser()
            val song = insertSong(insertAlbum())
            val artist = insertArtist("Artist")
            linkSongArtist(song, artist)
            insertLocal(other, song, 1_000)
            u
        }

        assertEquals(emptyList<UUID>(), service.recentArtists(user, 10).map { it.artist.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a song with two artists counts as a listen for both artists`(dialect: DbDialect) = runBlocking {
        setupWithLibrary(dialect)
        val (user, artistA, artistB) = transaction(database) {
            val u = insertUser()
            val song = insertSong(insertAlbum())
            val artistA = insertArtist("Artist A")
            val artistB = insertArtist("Artist B")
            linkSongArtist(song, artistA)
            linkSongArtist(song, artistB)
            insertLocal(u, song, 5_000)
            Triple(u, artistA, artistB)
        }

        val result = service.recentArtists(user, 10)

        assertEquals(setOf(artistA, artistB), result.map { it.artist.id }.toSet())
        assertTrue(result.all { it.lastListenedAt == 5_000L })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentAlbums returns distinct albums ordered by their latest listen`(dialect: DbDialect) = runBlocking {
        setupWithLibrary(dialect)
        val (user, albumOld, albumNew) = transaction(database) {
            val u = insertUser()
            val albumOld = insertAlbum("Old Album")
            val albumNew = insertAlbum("New Album")
            val songOld1 = insertSong(albumOld)
            val songOld2 = insertSong(albumOld)
            val songNew = insertSong(albumNew)
            insertLocal(u, songOld1, 1_000)
            insertLocal(u, songOld2, 2_000)
            insertLocal(u, songNew, 5_000)
            Triple(u, albumOld, albumNew)
        }

        val result = service.recentAlbums(user, 10)

        assertEquals(listOf(albumNew, albumOld), result.map { it.album.id })
        assertEquals(listOf(5_000L, 2_000L), result.map { it.lastListenedAt })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentAlbums respects the limit`(dialect: DbDialect) = runBlocking {
        setupWithLibrary(dialect)
        val (user, top, mid) = transaction(database) {
            val u = insertUser()
            val albums = (1..3).map { insertAlbum("Album $it") }
            albums.forEachIndexed { i, a -> insertLocal(u, insertSong(a), 1_000L * (i + 1)) }
            Triple(u, albums[2], albums[1])
        }

        val result = service.recentAlbums(user, 2)

        assertEquals(listOf(top, mid), result.map { it.album.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentAlbums excludes other users' listens`(dialect: DbDialect) = runBlocking {
        setupWithLibrary(dialect)
        val user = transaction(database) {
            val u = insertUser()
            val other = insertUser()
            val song = insertSong(insertAlbum())
            insertLocal(other, song, 1_000)
            u
        }

        assertEquals(emptyList<UUID>(), service.recentAlbums(user, 10).map { it.album.id })
    }

    private fun insertLocalPlayed(userId: UUID, songId: UUID, at: Long, playedMs: Long) {
        ListenTable.insert {
            it[ListenTable.userId] = userId
            it[ListenTable.songId] = songId
            it[listenedAt] = at
            it[listenSource] = ListenSource.LOCAL
            it[msPlayed] = playedMs
        }
    }

    private fun insertRelinkRow(
        source: ListenSource,
        owner: UUID,
        songId: UUID,
        at: Long,
        playedSongId: UUID? = null,
        recordingMbid: UUID? = null,
        recordingMsid: UUID? = null,
        trackName: String? = null,
    ) {
        ListenTable.insert {
            if (source == ListenSource.LOCAL) it[ListenTable.userId] = owner else it[listenBrainzUserId] = owner
            it[ListenTable.songId] = songId
            it[ListenTable.playedSongId] = playedSongId
            it[ListenTable.recordingMbid] = recordingMbid
            it[ListenTable.recordingMsid] = recordingMsid
            it[ListenTable.trackName] = trackName
            it[listenedAt] = at
            it[listenSource] = source
        }
    }

    private fun insertOverride(owner: UUID, songId: UUID, mbid: UUID?, msid: UUID?, at: Long = 1) {
        ListenLinkTable.insert {
            it[ListenLinkTable.userId] = owner
            it[ListenLinkTable.songId] = songId
            it[recordingMbid] = mbid
            it[recordingMsid] = msid
            it[createdAt] = at
        }
    }

    private fun insertRedirect(owner: UUID, from: UUID, to: UUID) {
        ListenSongRedirectTable.insert {
            it[ListenSongRedirectTable.userId] = owner
            it[fromSongId] = from
            it[toSongId] = to
            it[createdAt] = 1
        }
    }

    private class RelinkFixture(
        val user: UUID,
        val lb: UUID,
        val songA: UUID,
        val songB: UUID,
        val played: UUID,
        val mbid: UUID,
        val mbidMsid1: UUID,
        val mbidMsid2: UUID,
        val msidOnly: UUID,
    )

    private fun relinkFixture(): RelinkFixture {
        val user = insertUser()
        val lb = insertLbUser()
        linkListenBrainzUser(user, lb)
        val album = insertAlbum()
        val songA = insertSong(album, title = "A")
        val songB = insertSong(album, title = "B")
        val played = insertSong(album, title = "Played")
        val mbid = UUID.randomUUID()
        val mbidMsid1 = UUID.randomUUID()
        val mbidMsid2 = UUID.randomUUID()
        val msidOnly = UUID.randomUUID()
        insertRelinkRow(ListenSource.LOCAL, user, songA, 100, trackName = "A old")
        insertRelinkRow(ListenSource.LOCAL, user, songA, 110, trackName = "A new")
        insertRelinkRow(ListenSource.LOCAL, user, songA, 500, playedSongId = played, trackName = "Played local")
        insertRedirect(user, played, songA)
        insertRelinkRow(ListenSource.LISTENBRAINZ, lb, songA, 200, recordingMbid = mbid, recordingMsid = mbidMsid1, trackName = "Mbid old")
        insertRelinkRow(ListenSource.LISTENBRAINZ, lb, songA, 210, recordingMbid = mbid, recordingMsid = mbidMsid1, trackName = "Mbid mid")
        insertRelinkRow(ListenSource.LISTENBRAINZ, lb, songA, 220, recordingMbid = mbid, recordingMsid = mbidMsid2, trackName = "Mbid new")
        insertRelinkRow(ListenSource.LISTENBRAINZ, lb, songA, 300, recordingMsid = msidOnly, trackName = "Msid old")
        insertRelinkRow(ListenSource.LISTENBRAINZ, lb, songA, 310, recordingMsid = msidOnly, trackName = "Msid new")
        insertRelinkRow(ListenSource.LISTENBRAINZ, lb, songA, 400, trackName = "Anonymous")
        insertOverride(user, songA, mbid, mbidMsid1)
        insertOverride(user, songA, null, msidOnly, at = 2)
        return RelinkFixture(user, lb, songA, songB, played, mbid, mbidMsid1, mbidMsid2, msidOnly)
    }

    private fun songIdsByTrack(): Map<String?, UUID?> = ListenTable.selectAll()
        .associate { it[ListenTable.trackName] to it[ListenTable.songId]?.value }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `linkedGroups lists local, MBID, MSID and anonymous groups in order`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val f = transaction(database) { relinkFixture() }

        val groups = service.linkedGroups(f.user, f.songA)

        assertEquals(
            listOf(
                LinkedListenGroupKey(ListenGroupSource.LISTENBRAINZ, recordingMbid = f.mbid),
                LinkedListenGroupKey(ListenGroupSource.LISTENBRAINZ, recordingMsid = f.msidOnly),
                LinkedListenGroupKey(ListenGroupSource.LOCAL, playedSongId = f.songA),
                LinkedListenGroupKey(ListenGroupSource.LOCAL, playedSongId = f.played),
                LinkedListenGroupKey(ListenGroupSource.LISTENBRAINZ),
            ),
            groups.map { it.key },
        )
        assertEquals(listOf(3L, 2L, 2L, 1L, 1L), groups.map { it.listenCount })
        assertEquals(listOf(220L, 310L, 110L, 500L, 400L), groups.map { it.lastListenedAt })
        assertEquals(
            listOf("Mbid new", "Msid new", "A new", "Played local", "Anonymous"),
            groups.map { it.trackName },
        )
        assertEquals(listOf(true, true, false, true, false), groups.map { it.linkedManually })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `relinking one ListenBrainz group moves only that group and points its override at the target`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val f = transaction(database) { relinkFixture() }

            val result = service.relinkSong(
                f.user, f.songA, f.songB,
                listOf(LinkedListenGroupKey(ListenGroupSource.LISTENBRAINZ, recordingMbid = f.mbid)),
            )

            assertEquals(3, result.linkedListens)
            assertEquals(setOf(f.mbidMsid1, f.mbidMsid2), result.recordingMsids.toSet())
            transaction(database) {
                val byTrack = songIdsByTrack()
                assertEquals(f.songB, byTrack["Mbid old"])
                assertEquals(f.songB, byTrack["Mbid mid"])
                assertEquals(f.songB, byTrack["Mbid new"])
                listOf("A old", "A new", "Played local", "Msid old", "Msid new", "Anonymous").forEach {
                    assertEquals(f.songA, byTrack[it], it)
                }
                val mbidOverrides = ListenLinkTable.selectAll().where { ListenLinkTable.recordingMbid eq f.mbid }.toList()
                assertEquals(1, mbidOverrides.size)
                assertEquals(f.songB, mbidOverrides.single()[ListenLinkTable.songId].value)
                val msidOverride = ListenLinkTable.selectAll().where { ListenLinkTable.recordingMsid eq f.msidOnly }.single()
                assertEquals(f.songA, msidOverride[ListenLinkTable.songId].value)
            }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `relinking all groups moves every listen`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val f = transaction(database) { relinkFixture() }

        val result = service.relinkSong(f.user, f.songA, f.songB, null)

        assertEquals(9, result.linkedListens)
        transaction(database) {
            assertTrue(songIdsByTrack().values.all { it == f.songB })
            val played = ListenTable.selectAll().where { ListenTable.trackName eq "Played local" }.single()
            assertEquals(f.played, played[ListenTable.playedSongId]?.value)
            val own = ListenTable.selectAll().where { ListenTable.trackName eq "A new" }.single()
            assertEquals(f.songA, own[ListenTable.playedSongId]?.value)
            val redirects = ListenSongRedirectTable.selectAll().associate {
                it[ListenSongRedirectTable.fromSongId].value to it[ListenSongRedirectTable.toSongId].value
            }
            assertEquals(mapOf(f.played to f.songB, f.songA to f.songB), redirects)
            assertTrue(ListenLinkTable.selectAll().all { it[ListenLinkTable.songId].value == f.songB })
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `relinking a local group redirects later plays and relinking back undoes it`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, songA, songB) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            Triple(u, insertSong(album, title = "A"), insertSong(album, title = "B"))
        }
        service.ingestLocal(user, songA, 100, 50)
        val key = LinkedListenGroupKey(ListenGroupSource.LOCAL, playedSongId = songA)

        assertEquals(1, service.relinkSong(user, songA, songB, listOf(key)).linkedListens)
        service.ingestLocal(user, songA, 200, 50)

        transaction(database) {
            val rows = ListenTable.selectAll().toList()
            assertEquals(listOf(songB, songB), rows.map { it[ListenTable.songId]?.value })
            assertEquals(listOf(songA, songA), rows.map { it[ListenTable.playedSongId]?.value })
            val redirect = ListenSongRedirectTable.selectAll().single()
            assertEquals(songA, redirect[ListenSongRedirectTable.fromSongId].value)
            assertEquals(songB, redirect[ListenSongRedirectTable.toSongId].value)
        }

        assertEquals(2, service.relinkSong(user, songB, songA, listOf(key)).linkedListens)
        service.ingestLocal(user, songA, 300, 50)

        transaction(database) {
            assertEquals(0L, ListenSongRedirectTable.selectAll().count())
            val rows = ListenTable.selectAll().toList()
            assertEquals(listOf(songA, songA, songA), rows.map { it[ListenTable.songId]?.value })
            assertTrue(rows.all { it[ListenTable.playedSongId] == null })
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `relinking an MBID group removes an older override on its other MSID`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val f = transaction(database) {
            val f = relinkFixture()
            insertOverride(f.user, f.songA, null, f.mbidMsid2, at = 0)
            f
        }

        service.relinkSong(
            f.user, f.songA, f.songB,
            listOf(LinkedListenGroupKey(ListenGroupSource.LISTENBRAINZ, recordingMbid = f.mbid)),
        )

        transaction(database) {
            assertEquals(0L, ListenLinkTable.selectAll().where { ListenLinkTable.recordingMsid eq f.mbidMsid2 }.count())
            val forMbid = ListenLinkTable.selectAll().where { ListenLinkTable.recordingMbid eq f.mbid }.single()
            assertEquals(f.songB, forMbid[ListenLinkTable.songId].value)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `relinkSong rejects the same song and an unknown target`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val f = transaction(database) { relinkFixture() }

        assertThrows<IllegalArgumentException> { service.relinkSong(f.user, f.songA, f.songA, null) }
        assertThrows<IllegalArgumentException> { service.relinkSong(f.user, f.songA, UUID.randomUUID(), null) }

        transaction(database) {
            assertTrue(songIdsByTrack().values.all { it == f.songA })
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `relinkSong announces listens only when something moved`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, songA, songB) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            Triple(u, insertSong(album, title = "A"), insertSong(album, title = "B"))
        }

        service.relinkSong(user, songA, songB, null)
        verify(exactly = 0) { changeNotifier.notify(user, ChangeTopic.LISTENS) }

        service.ingestLocal(user, songA, 100, 50)
        verify(exactly = 1) { changeNotifier.notify(user, ChangeTopic.LISTENS) }

        service.relinkSong(
            user, songA, songB,
            listOf(LinkedListenGroupKey(ListenGroupSource.LOCAL, playedSongId = UUID.randomUUID())),
        )
        verify(exactly = 1) { changeNotifier.notify(user, ChangeTopic.LISTENS) }

        service.relinkSong(user, songA, songB, null)
        verify(exactly = 2) { changeNotifier.notify(user, ChangeTopic.LISTENS) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `relinking an anonymous ListenBrainz group persists no override`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val f = transaction(database) { relinkFixture() }
        transaction(database) { ListenLinkTable.deleteAll() }

        val result = service.relinkSong(
            f.user, f.songA, f.songB,
            listOf(LinkedListenGroupKey(ListenGroupSource.LISTENBRAINZ)),
        )

        assertEquals(1, result.linkedListens)
        assertEquals(emptyList<UUID>(), result.recordingMsids)
        transaction(database) {
            assertEquals(0L, ListenLinkTable.selectAll().count())
            assertEquals(f.songB, songIdsByTrack()["Anonymous"])
            assertFalse(songIdsByTrack().filterKeys { it != "Anonymous" }.values.any { it == f.songB })
            assertNull(ListenTable.selectAll().where { ListenTable.trackName eq "Anonymous" }.single()[ListenTable.playedSongId])
        }
    }

    private data class Quad(val a: UUID, val b: UUID, val c: UUID, val d: UUID)

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ingesting and linking listens announce the listens of the affected users`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mbid = UUID.randomUUID()
        val (user, linkedUser, otherUser, lb, song) = transaction(database) {
            val u = insertUser()
            val linked = insertUser()
            val other = insertUser()
            val lb = insertLbUser()
            linkListenBrainzUser(u, lb)
            linkListenBrainzUser(linked, lb)
            val song = insertSong(insertAlbum())
            listOf(u, linked, other, lb, song)
        }

        service.ingestLocal(user, song, 500, 250)
        verify(exactly = 1) { changeNotifier.notify(user, ChangeTopic.LISTENS) }

        service.ingestListenBrainz(lb, listOf(IncomingListen(listenedAtMs = 600, recordingMbid = mbid)))
        verify(exactly = 2) { changeNotifier.notify(user, ChangeTopic.LISTENS) }
        verify(exactly = 1) { changeNotifier.notify(linkedUser, ChangeTopic.LISTENS) }

        service.linkUnmatched(user, song, null, mbid)
        verify(exactly = 3) { changeNotifier.notify(user, ChangeTopic.LISTENS) }
        verify(exactly = 1) { changeNotifier.notify(linkedUser, ChangeTopic.LISTENS) }
        verify(exactly = 0) { changeNotifier.notify(otherUser, any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentListens includes the local and ListenBrainz listens of a linked partner`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, own, partnerLocal, partnerLb) = transaction(database) {
            val u = insertUser()
            val partner = insertUser()
            val stranger = insertUser()
            val partnerAccount = insertLbUser()
            linkListenBrainzUser(partner, partnerAccount)
            linkListenShare(u, partner)
            val album = insertAlbum()
            val own = insertSong(album)
            val partnerLocal = insertSong(album)
            val partnerLb = insertSong(album)
            insertLocal(u, own, 10_000)
            insertLocal(partner, partnerLocal, 20_000)
            insertLb(partnerAccount, partnerLb, 30_000)
            insertLocal(stranger, insertSong(album), 40_000)
            Quad(u, own, partnerLocal, partnerLb)
        }

        assertEquals(listOf(partnerLb, partnerLocal, own), service.recentListens(user, 10).map { it.song.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentArtists and recentAlbums include a linked partner's listens`(dialect: DbDialect) = runBlocking {
        setupWithLibrary(dialect)
        val (user, partner, album, artist) = transaction(database) {
            val u = insertUser()
            val partner = insertUser()
            val stranger = insertUser()
            linkListenShare(partner, u)
            val album = insertAlbum("Partner Album")
            val song = insertSong(album)
            val artist = insertArtist("Partner Artist")
            linkSongArtist(song, artist)
            insertLocal(partner, song, 5_000)
            val strangerSong = insertSong(insertAlbum("Stranger Album"))
            linkSongArtist(strangerSong, insertArtist("Stranger Artist"))
            insertLocal(stranger, strangerSong, 6_000)
            Quad(u, partner, album, artist)
        }

        assertEquals(listOf(artist), service.recentArtists(user, 10).map { it.artist.id })
        assertEquals(listOf(album), service.recentAlbums(user, 10).map { it.album.id })
        assertEquals(listOf(artist), service.recentArtists(partner, 10).map { it.artist.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `linkedGroups, relinkSong and linkUnmatched ignore a linked partner's listens`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mbid = UUID.randomUUID()
        val unmatchedMbid = UUID.randomUUID()
        val f = transaction(database) {
            val f = relinkFixture()
            val partner = insertUser()
            val partnerAccount = insertLbUser()
            linkListenBrainzUser(partner, partnerAccount)
            linkListenShare(f.user, partner)
            insertRelinkRow(ListenSource.LOCAL, partner, f.songA, 600, trackName = "Partner local")
            insertRelinkRow(ListenSource.LISTENBRAINZ, partnerAccount, f.songA, 610, recordingMbid = f.mbid, trackName = "Partner mbid")
            insertRelinkRow(ListenSource.LISTENBRAINZ, partnerAccount, f.songA, 620, recordingMbid = mbid, trackName = "Partner only")
            insertUnmatchedLb(partnerAccount, 630, recordingMbid = unmatchedMbid, trackName = "Partner unmatched")
            insertUnmatchedLb(f.lb, 640, recordingMbid = unmatchedMbid, trackName = "Own unmatched")
            f
        }

        val groups = service.linkedGroups(f.user, f.songA)
        assertEquals(listOf(3L, 2L, 2L, 1L, 1L), groups.map { it.listenCount })
        assertFalse(groups.any { it.key.recordingMbid == mbid })

        assertEquals(9, service.relinkSong(f.user, f.songA, f.songB, null).linkedListens)
        assertEquals(1, service.linkUnmatched(f.user, f.songB, null, unmatchedMbid).linkedListens)

        transaction(database) {
            val byTrack = songIdsByTrack()
            assertEquals(f.songA, byTrack["Partner local"])
            assertEquals(f.songA, byTrack["Partner mbid"])
            assertEquals(f.songA, byTrack["Partner only"])
            assertNull(byTrack["Partner unmatched"])
            assertEquals(f.songB, byTrack["Own unmatched"])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recentSeedWeights includes a partner's listens only when the link is used for recommendations`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val (user, own, partnerSong, linkId) = transaction(database) {
                val u = insertUser()
                val partner = insertUser()
                val partnerAccount = insertLbUser()
                linkListenBrainzUser(partner, partnerAccount)
                val linkId = linkListenShare(u, partner)
                val album = insertAlbum()
                val own = insertSong(album)
                val partnerSong = insertSong(album)
                val partnerLbSong = insertSong(album)
                insertLocal(u, own, 1_000)
                insertLocal(partner, partnerSong, 2_000)
                insertLb(partnerAccount, partnerLbSong, 3_000)
                Quad(u, own, partnerSong, linkId)
            }

            assertEquals(setOf(own), service.recentSeedWeights(user, 500).keys)

            transaction(database) {
                ListenShareTable.update({ ListenShareTable.id eq linkId }) { it[useForRecommendations] = true }
            }
            val weights = service.recentSeedWeights(user, 500)
            assertEquals(3, weights.size)
            assertTrue(own in weights && partnerSong in weights)
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listen changes are announced to linked partners`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user, partner, stranger, song) = transaction(database) {
            val u = insertUser()
            val partner = insertUser()
            val stranger = insertUser()
            linkListenShare(u, partner)
            Quad(u, partner, stranger, insertSong(insertAlbum()))
        }

        service.ingestLocal(user, song, 500, 250)
        verify(exactly = 1) { changeNotifier.notify(user, ChangeTopic.LISTENS) }
        verify(exactly = 1) { changeNotifier.notify(partner, ChangeTopic.LISTENS) }
        verify(exactly = 0) { changeNotifier.notify(stranger, any()) }

        service.ingestLocal(partner, song, 600, 250)
        verify(exactly = 2) { changeNotifier.notify(user, ChangeTopic.LISTENS) }
        verify(exactly = 2) { changeNotifier.notify(partner, ChangeTopic.LISTENS) }
    }
}
