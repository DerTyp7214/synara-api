package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.UserSong
import dev.dertyp.db.*
import dev.dertyp.plugins.HookBus
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.LinkResolverService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

    private fun setup(dialect: DbDialect) {
        songService = mockk()
        coEvery { songService.byIds(any(), any<UUID>()) } answers {
            firstArg<List<UUID>>().map { songStub(it) }
        }
        startKoin {
            modules(module {
                single<HookBus> { mockk(relaxed = true) }
                single { songService }
            })
        }
        database = TestDatabase.connect(dialect, "listen_test")
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
                MBReleaseGroupTable,
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
        service = ListenService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private val libraryTables = arrayOf(
        UserTable, ImageTable, ImageMetadataTable, AnimatedImageTable,
        ArtistTable, AlbumTable, SongTable, SongVariantTable, SongArtistTable, SongMusicBrainzTable, SongAudioDataTable,
        GenreTable, AlbumMusicBrainzTable, ArtistMusicBrainzTable, ArtistAliasTable, ArtistMemberTable,
        AlbumArtistTable, PlaylistTable, UserSongTable, TimecodeTagTable, UserPlaylistTable, SongGenreTable, ArtistGenreTable,
        AlbumGenreTable, PlaylistSongTable, UserPlaylistSongTable, SyncedLyricsTable, RecentReleaseTable,
        FollowedArtistTable, TranscodedSongTable, CustomMigrationTable, ScheduledTaskLogTable,
        ArtistSplitAliasTable, SyncServiceTable, SongProviderTable, AlbumProviderTable,
        CollectionTable, CollectionSongTable, CollectionAlbumTable, CollectionArtistTable, CollectionPlaylistTable,
        ListenBrainzUserTable, UserListenBrainzLinkTable, ListenTable, ListenLinkTable,
        *allMusicBrainzTables,
    )

    private fun setupWithLibrary(dialect: DbDialect) {
        val storageService = mockk<StorageService>(relaxed = true)
        every { storageService.albumsPath } returns null
        startKoin {
            modules(module {
                single<HookBus> { HookService() }
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
        database = TestDatabase.connect(dialect, "listen_library_test")
        transaction(database) { SchemaUtils.create(*libraryTables) }
        service = ListenService()
    }

    private fun songStub(id: UUID): UserSong {
        val song = mockk<UserSong>(relaxed = true)
        io.mockk.every { song.id } returns id
        return song
    }

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

    private fun insertSong(albumId: UUID, title: String = "Song", isrc: String? = null): UUID {
        val sid = UUID.randomUUID()
        SongTable.insert {
            it[id] = sid
            it[SongTable.title] = title
            it[SongTable.albumId] = albumId
            it[SongTable.isrc] = isrc
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
    fun `linkUnmatched by MBID links matching unmatched listens and stores an override`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mbid = UUID.randomUUID()
        val otherMbid = UUID.randomUUID()
        val msid = UUID.randomUUID()
        val (user, song) = transaction(database) {
            val u = insertUser()
            val lb = insertLbUser()
            link(u, lb)
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
            val linked = ListenTable.selectAll().where { ListenTable.recordingMbid eq mbid }.map { it[ListenTable.songId]?.value }
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
            link(u, lb)
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
            link(u, lb)
            val other = insertUser()
            val otherLb = insertLbUser()
            link(other, otherLb)
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
            link(u, lb)
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
            link(u, lb)
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
            link(u, lb)
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
            link(u, lb)
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
            link(u, lb)
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
            link(u, lb)
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
            link(u, lb)
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
            link(u, lb)
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

    private data class Quad(val a: UUID, val b: UUID, val c: UUID, val d: UUID)
}
