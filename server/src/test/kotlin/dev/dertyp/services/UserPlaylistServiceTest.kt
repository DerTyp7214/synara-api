package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.testing.entityChangeTables
import dev.dertyp.testing.entityEventsModule
import dev.dertyp.utils.ColorUtils
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class UserPlaylistServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: UserPlaylistService
    private lateinit var mbService: CachedMusicBrainzService
    private lateinit var songService: SongService

    fun setup(dialect: DbDialect) {
        mbService = mockk(relaxed = true)
        songService = mockk(relaxed = true)
        startKoin {
            modules(module {
                single { mockk<ImageService>(relaxed = true) }
                single<IMusicBrainzService> { mbService }
                single { mbService }
                single { songService }
                single { mockk<RedisSearchService>(relaxed = true) }
                includes(entityEventsModule())
            })
        }

        database = TestDatabase.connect(
            dialect, "user_playlist_test",
            UserTable,
            UserPlaylistTable,
            UserPlaylistSongTable,
            UserPlaylistShareTable,
            SongTable, SongVariantTable,
            AlbumTable,
            ArtistTable,
            SongArtistTable,
            AlbumArtistTable,
            ImageTable,
            ImageMetadataTable,
            SongMusicBrainzTable,
            MBRecordingTable,
            MBReleaseTable,
            *entityChangeTables,
        )
        service = UserPlaylistService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createPlaylistFromArtists should create a playlist and add songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val songId1 = UUID.randomUUID()
        val songId2 = UUID.randomUUID()
        val mbId1 = UUID.randomUUID()
        val mbId2 = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = "hash"
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
                it[isGroup] = false
            }
            val albumId = UUID.randomUUID()
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            MBRecordingTable.insert {
                it[id] = mbId1
                it[title] = "Song 1"
            }
            MBRecordingTable.insert {
                it[id] = mbId2
                it[title] = "Song 2"
            }
            SongTable.insert {
                it[id] = songId1
                it[title] = "Song 1"
                it[SongTable.albumId] = albumId
                it[filePath] = "path1"
                it[duration] = 1000
            }
            SongTable.insert {
                it[id] = songId2
                it[title] = "Song 2"
                it[SongTable.albumId] = albumId
                it[filePath] = "path2"
                it[duration] = 2000
            }
            SongMusicBrainzTable.insert {
                it[songId] = songId1
                it[musicBrainzId] = mbId1
            }
            SongMusicBrainzTable.insert {
                it[songId] = songId2
                it[musicBrainzId] = mbId2
            }
        }

        val song1 = UserSong(
            id = songId1, title = "Song 1", artists = emptyList(), album = null,
            duration = 1000, explicit = false, path = "path1", musicBrainzId = mbId1
        )
        val song2 = UserSong(
            id = songId2, title = "Song 2", artists = emptyList(), album = null,
            duration = 2000, explicit = false, path = "path2", musicBrainzId = mbId2
        )

        coEvery { songService.byArtist(0, 10, artistId, userId) } returns PaginatedResponse(
            listOf(song1, song2),
            2,
            0,
            10
        )
        coEvery { mbService.getRecording(mbId1) } returns MusicBrainzRecording(
            id = mbId1,
            releases = listOf(MusicBrainzRelease(id = UUID.randomUUID(), date = "2020-01-01"))
        )
        coEvery { mbService.getRecording(mbId2) } returns MusicBrainzRecording(
            id = mbId2,
            releases = listOf(MusicBrainzRelease(id = UUID.randomUUID(), date = "2010-01-01"))
        )

        val playlistId = service.createPlaylistFromArtists(
            userId,
            "Smart Playlist",
            listOf(artistId),
            10,
            ArtistPlaylistSortStrategy.MB_RELEASE_DATE
        )

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals("Smart Playlist", playlist?.name)
        assertEquals(listOf(songId1, songId2), playlist?.songs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createPlaylistFromArtists with MB_RELEASE_DATE_ASC should sort correctly`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val songId1 = UUID.randomUUID()
        val songId2 = UUID.randomUUID()
        val mbId1 = UUID.randomUUID()
        val mbId2 = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = "hash"
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
                it[isGroup] = false
            }
            val albumId = UUID.randomUUID()
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            MBRecordingTable.insert {
                it[id] = mbId1
                it[title] = "Song 1"
            }
            MBRecordingTable.insert {
                it[id] = mbId2
                it[title] = "Song 2"
            }
            SongTable.insert {
                it[id] = songId1
                it[title] = "Song 1"
                it[SongTable.albumId] = albumId
                it[filePath] = "path1"
                it[duration] = 1000
            }
            SongTable.insert {
                it[id] = songId2
                it[title] = "Song 2"
                it[SongTable.albumId] = albumId
                it[filePath] = "path2"
                it[duration] = 2000
            }
            SongMusicBrainzTable.insert {
                it[songId] = songId1
                it[musicBrainzId] = mbId1
            }
            SongMusicBrainzTable.insert {
                it[songId] = songId2
                it[musicBrainzId] = mbId2
            }
        }

        val song1 = UserSong(
            id = songId1, title = "Song 1", artists = emptyList(), album = null,
            duration = 1000, explicit = false, path = "path1", musicBrainzId = mbId1
        )
        val song2 = UserSong(
            id = songId2, title = "Song 2", artists = emptyList(), album = null,
            duration = 2000, explicit = false, path = "path2", musicBrainzId = mbId2
        )

        coEvery { songService.byArtist(0, 10, artistId, userId) } returns PaginatedResponse(
            listOf(song1, song2),
            2,
            0,
            10
        )
        coEvery { mbService.getRecording(mbId1) } returns MusicBrainzRecording(
            id = mbId1,
            releases = listOf(MusicBrainzRelease(id = UUID.randomUUID(), date = "2020-01-01"))
        )
        coEvery { mbService.getRecording(mbId2) } returns MusicBrainzRecording(
            id = mbId2,
            releases = listOf(MusicBrainzRelease(id = UUID.randomUUID(), date = "2010-01-01"))
        )

        val playlistId = service.createPlaylistFromArtists(
            userId,
            "Smart Playlist Asc",
            listOf(artistId),
            10,
            ArtistPlaylistSortStrategy.MB_RELEASE_DATE_ASC
        )

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals(listOf(songId2, songId1), playlist?.songs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return user playlist with cover blurHash`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val playlistId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val imageId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user"
                it[passwordHash] = "hash"
            }
            ImageTable.insert {
                it[id] = imageId
                it[path] = "test.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
                it[blurHash] = "user_playlist_blurhash"
            }
            UserPlaylistTable.insert {
                it[id] = playlistId
                it[name] = "User Playlist with Cover"
                it[UserPlaylistTable.imageId] = imageId
                it[creator] = userId
                it[description] = ""
            }
        }

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals(imageId, playlist?.imageId)
        assertEquals("user_playlist_blurhash", playlist?.blurHash)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return user playlist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val playlistId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = "hash"
            }
            UserPlaylistTable.insert {
                it[id] = playlistId
                it[name] = "My Playlist"
                it[description] = ""
                it[creator] = userId
            }
        }

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals("My Playlist", playlist?.name)
    }

    private class Scenario(
        val ownerId: UUID,
        val sharedUserId: UUID,
        val strangerId: UUID,
        val publicId: UUID,
        val sharedId: UUID,
        val hiddenId: UUID,
        val strangerPlaylistId: UUID,
    ) {
        val allIds get() = listOf(publicId, sharedId, hiddenId, strangerPlaylistId)
    }

    private val PaginatedResponse<UserPlaylist>.names: Set<String> get() = data.map { it.name }.toSet()

    private fun addUser(userName: String): UUID {
        val newUserId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = newUserId
                it[username] = userName
                it[passwordHash] = ""
            }
        }
        return newUserId
    }

    private fun addPlaylist(creatorId: UUID, playlistName: String, publicFlag: Boolean = false, cover: UUID? = null): UUID {
        val newPlaylistId = UUID.randomUUID()
        transaction(database) {
            UserPlaylistTable.insert {
                it[id] = newPlaylistId
                it[name] = playlistName
                it[description] = ""
                it[creator] = creatorId
                it[isPublic] = publicFlag
                if (cover != null) it[imageId] = cover
            }
        }
        return newPlaylistId
    }

    private fun addShare(forPlaylist: UUID, forUser: UUID, level: PlaylistAccess) {
        transaction(database) {
            UserPlaylistShareTable.insert {
                it[playlistId] = forPlaylist
                it[userId] = forUser
                it[access] = level
            }
        }
    }

    private fun shareRows(forPlaylist: UUID): Set<Pair<UUID, PlaylistAccess>> = transaction(database) {
        UserPlaylistShareTable.selectAll()
            .where { UserPlaylistShareTable.playlistId eq forPlaylist }
            .map { it[UserPlaylistShareTable.userId].value to it[UserPlaylistShareTable.access] }
            .toSet()
    }

    private fun shareRowCount(): Int = transaction(database) { UserPlaylistShareTable.selectAll().count().toInt() }

    private fun addRedCover(): UUID {
        val coverId = UUID.randomUUID()
        val (lightness, greenRed, blueYellow) = ColorUtils.rgbToLab(255, 0, 0)
        transaction(database) {
            ImageTable.insert {
                it[id] = coverId
                it[path] = "red.jpg"
                it[imageHash] = "red"
                it[origin] = "test"
            }
            ImageMetadataTable.insert {
                it[imageId] = coverId
                it[width] = 100
                it[height] = 100
                it[byteSize] = 1000
                it[primaryColor] = RED
                it[ImageMetadataTable.red] = 255
                it[ImageMetadataTable.green] = 0
                it[ImageMetadataTable.blue] = 0
                it[luminance] = 0.5
                it[labL] = lightness
                it[labA] = greenRed
                it[labB] = blueYellow
            }
        }
        return coverId
    }

    private fun scenario(): Scenario {
        val ownerId = addUser("owner")
        val sharedUserId = addUser("shared")
        val strangerId = addUser("stranger")
        val cover = addRedCover()
        val publicId = addPlaylist(ownerId, "Public Mix", publicFlag = true, cover = cover)
        val sharedId = addPlaylist(ownerId, "Shared Mix", cover = cover)
        val hiddenId = addPlaylist(ownerId, "Hidden Mix", cover = cover)
        val strangerPlaylistId = addPlaylist(strangerId, "Stranger Mix", cover = cover)
        addShare(sharedId, sharedUserId, PlaylistAccess.READ)
        return Scenario(ownerId, sharedUserId, strangerId, publicId, sharedId, hiddenId, strangerPlaylistId)
    }

    private val ownerNames = setOf("Public Mix", "Shared Mix", "Hidden Mix")
    private val sharedUserNames = setOf("Public Mix", "Shared Mix")
    private val strangerNames = setOf("Public Mix", "Stranger Mix")
    private val allNames = setOf("Public Mix", "Shared Mix", "Hidden Mix", "Stranger Mix")

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allPlaylists shows a viewer only public, own and shared playlists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()

        assertEquals(allNames, service.allPlaylists(null, 0, 50, null).names)
        assertEquals(ownerNames, service.allPlaylists(null, 0, 50, data.ownerId).names)
        assertEquals(sharedUserNames, service.allPlaylists(null, 0, 50, data.sharedUserId).names)
        assertEquals(strangerNames, service.allPlaylists(null, 0, 50, data.strangerId).names)
        assertEquals(setOf("Public Mix", "Shared Mix"), service.allPlaylists(data.ownerId, 0, 50, data.sharedUserId).names)
        assertEquals(setOf("Public Mix"), service.allPlaylists(data.ownerId, 0, 50, data.strangerId).names)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allPlaylists counts only the visible playlists in the total`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()

        val strangerPage = service.allPlaylists(null, 0, 1, data.strangerId)
        assertEquals(1, strangerPage.data.size)
        assertEquals(2, strangerPage.total)
        assertTrue(strangerPage.hasNextPage)

        val ownerFirst = service.allPlaylists(null, 0, 2, data.ownerId)
        val ownerSecond = service.allPlaylists(null, 1, 2, data.ownerId)
        assertEquals(3, ownerFirst.total)
        assertEquals(3, ownerSecond.total)
        assertEquals(2, ownerFirst.data.size)
        assertEquals(1, ownerSecond.data.size)
        assertEquals(ownerNames, ownerFirst.names + ownerSecond.names)
        assertFalse(ownerSecond.hasNextPage)

        assertEquals(4, service.allPlaylists(null, 0, 2, null).total)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch shows a viewer only public, own and shared playlists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()

        assertEquals(allNames, service.rankedSearch(null, 0, 50, "Mix", null).names)
        assertEquals(ownerNames, service.rankedSearch(null, 0, 50, "Mix", data.ownerId).names)
        assertEquals(sharedUserNames, service.rankedSearch(null, 0, 50, "Mix", data.sharedUserId).names)
        assertEquals(strangerNames, service.rankedSearch(null, 0, 50, "Mix", data.strangerId).names)
        assertEquals(setOf("Hidden Mix"), service.rankedSearch(null, 0, 50, "Hidden", data.ownerId).names)
        assertEquals(emptySet<String>(), service.rankedSearch(null, 0, 50, "Hidden", data.strangerId).names)
        assertEquals(emptySet<String>(), service.rankedSearch(null, 0, 50, "Hidden", data.sharedUserId).names)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch counts only the visible playlists in the total`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()

        val page = service.rankedSearch(null, 0, 1, "Mix", data.strangerId)
        assertEquals(1, page.data.size)
        assertEquals(2, page.total)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byColor shows a viewer only public, own and shared playlists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()

        assertEquals(allNames, service.byColor(null, 0, 50, RED, 5, null).names)
        assertEquals(ownerNames, service.byColor(null, 0, 50, RED, 5, data.ownerId).names)
        assertEquals(sharedUserNames, service.byColor(null, 0, 50, RED, 5, data.sharedUserId).names)
        assertEquals(strangerNames, service.byColor(null, 0, 50, RED, 5, data.strangerId).names)
        assertEquals(setOf("Public Mix"), service.byColor(data.ownerId, 0, 50, RED, 5, data.strangerId).names)
        assertEquals(2, service.byColor(null, 0, 1, RED, 5, data.strangerId).total)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId shows a viewer only public, own and shared playlists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()

        suspend fun visible(viewer: UUID?) = data.allIds.filter { service.byId(it, viewer) != null }.toSet()

        assertEquals(data.allIds.toSet(), visible(null))
        assertEquals(setOf(data.publicId, data.sharedId, data.hiddenId), visible(data.ownerId))
        assertEquals(setOf(data.publicId, data.sharedId), visible(data.sharedUserId))
        assertEquals(setOf(data.publicId, data.strangerPlaylistId), visible(data.strangerId))
        assertNotNull(service.byId(data.hiddenId))
        assertNull(service.byId(data.hiddenId, data.strangerId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byIds shows a viewer only public, own and shared playlists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()

        assertEquals(data.allIds, service.byIds(data.allIds, null).map { it.id })
        assertEquals(data.allIds, service.byIds(data.allIds).map { it.id })
        assertEquals(
            listOf(data.publicId, data.sharedId, data.hiddenId),
            service.byIds(data.allIds, data.ownerId).map { it.id }
        )
        assertEquals(listOf(data.publicId, data.sharedId), service.byIds(data.allIds, data.sharedUserId).map { it.id })
        assertEquals(
            listOf(data.publicId, data.strangerPlaylistId),
            service.byIds(data.allIds, data.strangerId).map { it.id }
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `sharedPlaylists lists only the playlists shared with the user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()

        val shared = service.sharedPlaylists(data.sharedUserId, 0, 10)
        assertEquals(setOf("Shared Mix"), shared.names)
        assertEquals(1, shared.total)
        assertEquals(emptySet<String>(), service.sharedPlaylists(data.ownerId, 0, 10).names)
        assertEquals(emptySet<String>(), service.sharedPlaylists(data.strangerId, 0, 10).names)

        addShare(data.publicId, data.strangerId, PlaylistAccess.WRITE)
        addShare(data.hiddenId, data.strangerId, PlaylistAccess.READ)
        val strangerShared = service.sharedPlaylists(data.strangerId, 0, 1)
        assertEquals(2, strangerShared.total)
        assertEquals(1, strangerShared.data.size)
        assertEquals(
            setOf("Public Mix", "Hidden Mix"),
            service.sharedPlaylists(data.strangerId, 0, 10).names
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setShare inserts a share and a viewer then sees the playlist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()
        assertNull(service.byId(data.hiddenId, data.sharedUserId))

        service.setShare(data.hiddenId, data.sharedUserId, PlaylistAccess.WRITE)

        assertEquals(setOf(data.sharedUserId to PlaylistAccess.WRITE), shareRows(data.hiddenId))
        val seen = service.byId(data.hiddenId, data.sharedUserId)
        assertNotNull(seen)
        assertEquals(listOf(PlaylistShare(data.sharedUserId, PlaylistAccess.WRITE)), seen?.shares)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setShare changes the access of an existing share and keeps one row`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()
        val createdAt = transaction(database) {
            UserPlaylistShareTable.selectAll()
                .where { UserPlaylistShareTable.playlistId eq data.sharedId }
                .single()[UserPlaylistShareTable.createdAt]
        }

        service.setShare(data.sharedId, data.sharedUserId, PlaylistAccess.WRITE)
        assertEquals(setOf(data.sharedUserId to PlaylistAccess.WRITE), shareRows(data.sharedId))
        service.setShare(data.sharedId, data.sharedUserId, PlaylistAccess.READ)
        assertEquals(setOf(data.sharedUserId to PlaylistAccess.READ), shareRows(data.sharedId))

        assertEquals(1, shareRowCount())
        val kept = transaction(database) {
            UserPlaylistShareTable.selectAll()
                .where { UserPlaylistShareTable.playlistId eq data.sharedId }
                .single()[UserPlaylistShareTable.createdAt]
        }
        assertEquals(createdAt, kept)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setShare refuses an unknown playlist, the owner and an unknown user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()
        val before = shareRowCount()

        assertThrows<IllegalArgumentException> {
            runBlocking { service.setShare(UUID.randomUUID(), data.sharedUserId, PlaylistAccess.READ) }
        }
        assertThrows<IllegalArgumentException> {
            runBlocking { service.setShare(data.hiddenId, data.ownerId, PlaylistAccess.READ) }
        }
        assertThrows<IllegalArgumentException> {
            runBlocking { service.setShare(data.hiddenId, UUID.randomUUID(), PlaylistAccess.READ) }
        }

        assertEquals(before, shareRowCount())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `removeShare removes only that share`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()
        addShare(data.sharedId, data.strangerId, PlaylistAccess.WRITE)

        assertTrue(service.removeShare(data.sharedId, data.sharedUserId))

        assertEquals(setOf(data.strangerId to PlaylistAccess.WRITE), shareRows(data.sharedId))
        assertNull(service.byId(data.sharedId, data.sharedUserId))
        assertFalse(service.removeShare(data.sharedId, data.sharedUserId))
        assertFalse(service.removeShare(data.hiddenId, data.sharedUserId))
        assertFalse(service.removeShare(UUID.randomUUID(), data.sharedUserId))
        assertEquals(1, shareRowCount())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setPublic changes the visibility for everyone`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()
        assertNull(service.byId(data.hiddenId, data.strangerId))
        assertFalse(service.byId(data.hiddenId)!!.isPublic)

        assertTrue(service.setPublic(data.hiddenId, true))

        val seen = service.byId(data.hiddenId, data.strangerId)
        assertNotNull(seen)
        assertTrue(seen!!.isPublic)
        assertTrue("Hidden Mix" in service.allPlaylists(null, 0, 50, data.strangerId).names)

        assertTrue(service.setPublic(data.hiddenId, false))
        assertNull(service.byId(data.hiddenId, data.strangerId))
        assertFalse("Hidden Mix" in service.allPlaylists(null, 0, 50, data.strangerId).names)
        assertFalse(service.setPublic(UUID.randomUUID(), true))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `replaceShares drops unknown users, the owner and duplicates`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()

        service.replaceShares(
            data.sharedId,
            listOf(
                PlaylistShare(data.strangerId, PlaylistAccess.WRITE),
                PlaylistShare(data.strangerId, PlaylistAccess.READ),
                PlaylistShare(data.ownerId, PlaylistAccess.READ),
                PlaylistShare(UUID.randomUUID(), PlaylistAccess.READ),
            )
        )

        assertEquals(setOf(data.strangerId to PlaylistAccess.WRITE), shareRows(data.sharedId))

        service.replaceShares(data.sharedId, emptyList())
        assertEquals(emptySet<Pair<UUID, PlaylistAccess>>(), shareRows(data.sharedId))

        val before = shareRowCount()
        service.replaceShares(UUID.randomUUID(), listOf(PlaylistShare(data.strangerId, PlaylistAccess.READ)))
        assertEquals(before, shareRowCount())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `transferOwnership makes the user the owner and the previous owner a writer`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()

        assertTrue(service.transferOwnership(data.sharedId, data.sharedUserId))

        val transferred = service.byId(data.sharedId)!!
        assertEquals(data.sharedUserId, transferred.creator)
        assertEquals(setOf(data.ownerId to PlaylistAccess.WRITE), shareRows(data.sharedId))
        assertEquals(listOf(PlaylistShare(data.ownerId, PlaylistAccess.WRITE)), transferred.shares)

        assertTrue(service.transferOwnership(data.hiddenId, data.strangerId))
        assertEquals(data.strangerId, service.byId(data.hiddenId)!!.creator)
        assertEquals(setOf(data.ownerId to PlaylistAccess.WRITE), shareRows(data.hiddenId))
        assertNotNull(service.byId(data.hiddenId, data.ownerId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `transferOwnership refuses an unknown playlist, the current owner and an unknown user`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val data = scenario()
            val before = shareRowCount()

            assertFalse(service.transferOwnership(UUID.randomUUID(), data.sharedUserId))
            assertFalse(service.transferOwnership(data.hiddenId, data.ownerId))
            assertFalse(service.transferOwnership(data.hiddenId, UUID.randomUUID()))

            assertEquals(data.ownerId, service.byId(data.hiddenId)!!.creator)
            assertEquals(before, shareRowCount())
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `shares are mapped onto the playlist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()
        addShare(data.sharedId, data.strangerId, PlaylistAccess.WRITE)

        val playlist = service.byId(data.sharedId)!!
        assertEquals(
            setOf(
                PlaylistShare(data.sharedUserId, PlaylistAccess.READ),
                PlaylistShare(data.strangerId, PlaylistAccess.WRITE),
            ),
            playlist.shares.toSet()
        )
        assertEquals(2, playlist.shares.size)
        assertEquals(emptyList<PlaylistShare>(), service.byId(data.hiddenId)!!.shares)
        assertEquals(playlist.shares, service.allPlaylists(data.ownerId, 0, 50).data.single { it.id == data.sharedId }.shares)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting a user removes the shares of that user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()
        addShare(data.hiddenId, data.sharedUserId, PlaylistAccess.WRITE)
        addShare(data.hiddenId, data.strangerId, PlaylistAccess.READ)
        assertEquals(3, shareRowCount())

        transaction(database) { UserTable.deleteWhere { UserTable.id eq data.sharedUserId } }

        assertEquals(1, shareRowCount())
        assertEquals(setOf(data.strangerId to PlaylistAccess.READ), shareRows(data.hiddenId))
        assertEquals(emptySet<Pair<UUID, PlaylistAccess>>(), shareRows(data.sharedId))
        assertNotNull(service.byId(data.sharedId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting a playlist removes its shares`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = scenario()
        addShare(data.hiddenId, data.strangerId, PlaylistAccess.READ)
        assertEquals(2, shareRowCount())

        assertTrue(service.delete(data.sharedId))

        assertEquals(1, shareRowCount())
        assertEquals(emptySet<Pair<UUID, PlaylistAccess>>(), shareRows(data.sharedId))
        assertEquals(setOf(data.strangerId to PlaylistAccess.READ), shareRows(data.hiddenId))
    }

    companion object {
        private val RED = 0xFFFF0000.toInt()
    }
}
