package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.InsertableRadioChannel
import dev.dertyp.data.RadioChannelItemType
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.LinkResolverService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioChannelServiceTest : KoinTest {

    private val creatorId = UUID.randomUUID()
    private val channelAlbum = UUID.randomUUID()
    private val channelArtist = UUID.randomUUID()
    private val otherAlbum = UUID.randomUUID()
    private val songExplicit = UUID.randomUUID()    // member via SONG item
    private val songInAlbum = UUID.randomUUID()     // member via ALBUM item
    private val songByArtistA = UUID.randomUUID()   // member via ARTIST item
    private val songByArtistB = UUID.randomUUID()   // member via ARTIST item
    private val songUnrelated = UUID.randomUUID()   // not a member

    private val members = setOf(songExplicit, songInAlbum, songByArtistA, songByArtistB)

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "radio_channel_test")
        dbQuery {
            SchemaUtils.create(
                ImageTable, AnimatedImageTable, UserTable, AlbumTable, ArtistTable, ArtistAliasTable,
                SongTable, SongVariantTable, SongArtistTable, AlbumArtistTable,
                RadioChannelTable, RadioChannelSongTable, RadioChannelArtistTable, RadioChannelAlbumTable,
            )
            UserTable.insert { it[id] = creatorId; it[username] = "admin"; it[passwordHash] = "x" }
            AlbumTable.insert { it[id] = channelAlbum; it[name] = "Channel Album" }
            AlbumTable.insert { it[id] = otherAlbum; it[name] = "Other Album" }
            ArtistTable.insert { it[id] = channelArtist; it[name] = "Channel Artist" }

            fun song(songId: UUID, album: UUID) = SongTable.insert { it[id] = songId; it[title] = "s"; it[albumId] = album }
            song(songExplicit, otherAlbum)
            song(songInAlbum, channelAlbum)
            song(songByArtistA, otherAlbum)
            song(songByArtistB, otherAlbum)
            song(songUnrelated, otherAlbum)
            SongArtistTable.insert { it[songId] = songByArtistA; it[artistId] = channelArtist }
            SongArtistTable.insert { it[songId] = songByArtistB; it[artistId] = channelArtist }
        }

        startKoin { modules(module { single { mockk<ImageService>() } }) }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private suspend fun configuredChannel(service: RadioChannelService): UUID {
        val id = service.create(InsertableRadioChannel(name = "Chill"), creatorId)
        assertTrue(service.addItem(id, RadioChannelItemType.SONG, songExplicit))
        assertTrue(service.addItem(id, RadioChannelItemType.ARTIST, channelArtist))
        assertTrue(service.addItem(id, RadioChannelItemType.ALBUM, channelAlbum))
        return id
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `randomSongs draws only configured content`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = RadioChannelService()
        val id = configuredChannel(service)

        assertEquals(members, service.randomSongs(id, emptySet(), 100).toSet())

        val channel = service.byId(id)!!
        assertEquals(1, channel.songCount)
        assertEquals(1, channel.artistCount)
        assertEquals(1, channel.albumCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `randomSongs honours the exclude set`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = RadioChannelService()
        val id = configuredChannel(service)

        val drawn = service.randomSongs(id, setOf(songExplicit, songInAlbum), 100).toSet()
        assertEquals(setOf(songByArtistA, songByArtistB), drawn, "excluded members must not be drawn")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `list hides drafts unless includeDisabled`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = RadioChannelService()

        val published = service.create(InsertableRadioChannel(name = "Published", enabled = true), creatorId)
        val draft = service.create(InsertableRadioChannel(name = "Draft", enabled = false), creatorId)

        val visible = service.list(includeDisabled = false).map { it.id }
        assertTrue(published in visible)
        assertTrue(draft !in visible)

        val all = service.list(includeDisabled = true).map { it.id }
        assertTrue(published in all && draft in all)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `addItem rejects unknown entity`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = RadioChannelService()
        val id = service.create(InsertableRadioChannel(name = "X"), creatorId)
        assertTrue(!service.addItem(id, RadioChannelItemType.SONG, UUID.randomUUID()), "non-existent song is rejected")
    }

    private val searchTables = arrayOf(
        UserTable, ImageTable, ImageMetadataTable, AnimatedImageTable,
        ArtistTable, AlbumTable, SongTable, SongVariantTable, SongArtistTable, SongMusicBrainzTable, SongAudioDataTable,
        GenreTable, AlbumMusicBrainzTable, ArtistMusicBrainzTable, ArtistAliasTable, ArtistMemberTable,
        AlbumArtistTable, PlaylistTable, UserSongTable, UserPlaylistTable, SongGenreTable, ArtistGenreTable,
        AlbumGenreTable, PlaylistSongTable, UserPlaylistSongTable, SyncedLyricsTable, RecentReleaseTable,
        FollowedArtistTable, TranscodedSongTable, CustomMigrationTable, ScheduledTaskLogTable,
        ArtistSplitAliasTable, SyncServiceTable, SongProviderTable, AlbumProviderTable,
        RadioChannelTable, RadioChannelSongTable, RadioChannelArtistTable, RadioChannelAlbumTable,
        *allMusicBrainzTables,
    )

    private fun setupSearch(dialect: DbDialect) = runBlocking {
        val storageService = mockk<StorageService>(relaxed = true)
        every { storageService.albumsPath } returns null
        startKoin {
            modules(module {
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
        TestDatabase.connect(dialect, "radio_channel_search_test")
        dbQuery {
            SchemaUtils.create(*searchTables)
            UserTable.insert { it[id] = creatorId; it[username] = "admin"; it[passwordHash] = "x" }
        }
    }

    private fun insertNamedAlbum(name: String): UUID {
        val aid = UUID.randomUUID()
        AlbumTable.insert { it[id] = aid; it[AlbumTable.name] = name }
        return aid
    }

    private fun insertNamedArtist(name: String): UUID {
        val aid = UUID.randomUUID()
        ArtistTable.insert { it[id] = aid; it[ArtistTable.name] = name }
        return aid
    }

    private fun insertNamedSong(albumId: UUID, title: String): UUID {
        val sid = UUID.randomUUID()
        SongTable.insert {
            it[id] = sid
            it[SongTable.title] = title
            it[SongTable.albumId] = albumId
            it[fileSize] = 0
        }
        return sid
    }

    private data class SearchFixture(
        val channelId: UUID,
        val directSong: UUID,
        val albumSong: UUID,
        val artistSong: UUID,
        val albumArtistSong: UUID,
        val artist: UUID,
        val album: UUID,
    )

    private suspend fun searchFixture(service: RadioChannelService): SearchFixture {
        lateinit var fixture: SearchFixture
        val channelId = service.create(InsertableRadioChannel(name = "Chill"), creatorId)
        dbQuery {
            val artist = insertNamedArtist("Alpha Artist")

            val directSong = insertNamedSong(insertNamedAlbum("Direct Album"), "Alpha One")

            val channelAlbum = insertNamedAlbum("Second Album")
            val albumSong = insertNamedSong(channelAlbum, "Alpha Two")

            val artistSong = insertNamedSong(insertNamedAlbum("Third Album"), "Alpha Three")
            SongArtistTable.insert { it[songId] = artistSong; it[artistId] = artist }

            val artistAlbum = insertNamedAlbum("Fourth Album")
            AlbumArtistTable.insert { it[albumId] = artistAlbum; it[artistId] = artist }
            val albumArtistSong = insertNamedSong(artistAlbum, "Alpha Four")

            insertNamedSong(insertNamedAlbum("Fifth Album"), "Alpha Five")

            fixture = SearchFixture(channelId, directSong, albumSong, artistSong, albumArtistSong, artist, channelAlbum)
        }
        assertTrue(service.addItem(channelId, RadioChannelItemType.SONG, fixture.directSong))
        assertTrue(service.addItem(channelId, RadioChannelItemType.ALBUM, fixture.album))
        assertTrue(service.addItem(channelId, RadioChannelItemType.ARTIST, fixture.artist))
        return fixture
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch returns explicit and implicit songs flagged by membership`(dialect: DbDialect) = runBlocking {
        setupSearch(dialect)
        val service = RadioChannelService()
        val f = searchFixture(service)

        val results = service.rankedSearch(f.channelId, "Alpha", explicit = true, page = 0, pageSize = 50, userId = creatorId)
        val byId = results.songs.data.associateBy { it.song.id }

        assertEquals(setOf(f.directSong, f.albumSong, f.artistSong, f.albumArtistSong), byId.keys)
        assertTrue(byId.getValue(f.directSong).explicitMember)
        assertFalse(byId.getValue(f.albumSong).explicitMember)
        assertFalse(byId.getValue(f.artistSong).explicitMember)
        assertFalse(byId.getValue(f.albumArtistSong).explicitMember)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch scopes artists and albums to channel items`(dialect: DbDialect) = runBlocking {
        setupSearch(dialect)
        val service = RadioChannelService()
        val channelId = service.create(InsertableRadioChannel(name = "Chill"), creatorId)

        lateinit var artistIn: UUID
        lateinit var albumIn: UUID
        dbQuery {
            artistIn = insertNamedArtist("Alpha Artist")
            insertNamedArtist("Alpha Other Artist")
            albumIn = insertNamedAlbum("Alpha Album")
            insertNamedAlbum("Alpha Other Album")
        }
        assertTrue(service.addItem(channelId, RadioChannelItemType.ARTIST, artistIn))
        assertTrue(service.addItem(channelId, RadioChannelItemType.ALBUM, albumIn))

        val results = service.rankedSearch(channelId, "Alpha", explicit = true, page = 0, pageSize = 50, userId = creatorId)

        assertEquals(listOf(artistIn), results.artists.data.map { it.id })
        assertEquals(listOf(albumIn), results.albums.data.map { it.id })
        assertTrue(results.songs.data.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch with an empty query lists all configured content`(dialect: DbDialect) = runBlocking {
        setupSearch(dialect)
        val service = RadioChannelService()
        val f = searchFixture(service)

        val results = service.rankedSearch(f.channelId, "", explicit = true, page = 0, pageSize = 50, userId = creatorId)

        assertEquals(
            setOf(f.directSong, f.albumSong, f.artistSong, f.albumArtistSong),
            results.songs.data.map { it.song.id }.toSet(),
        )
        assertEquals(listOf(f.artist), results.artists.data.map { it.id })
        assertEquals(listOf(f.album), results.albums.data.map { it.id })
    }
}
