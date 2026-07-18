package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.UserSong
import dev.dertyp.db.*
import dev.dertyp.plugins.HookBus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
                SongTable,
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

    private data class Quad(val a: UUID, val b: UUID, val c: UUID, val d: UUID)
}
