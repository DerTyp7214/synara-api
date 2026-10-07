package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.LinkResolverService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.services.release.ReleaseArtistService
import dev.dertyp.testing.entityChangeTables
import dev.dertyp.testing.entityEventsModule
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

abstract class EntityChangeLibraryTest : KoinTest {
    protected lateinit var database: Database
    protected lateinit var songService: SongService
    protected lateinit var albumService: AlbumService
    protected lateinit var artistService: ArtistService
    protected lateinit var libraryMergeService: LibraryMergeService

    protected val cachedMusicBrainzService = mockk<CachedMusicBrainzService>(relaxed = true)
    protected val imageService = mockk<ImageService>(relaxed = true)
    protected val pluginManager = mockk<PluginManager>(relaxed = true)
    protected val linkResolverService = mockk<LinkResolverService>(relaxed = true)
    protected val storageService = mockk<StorageService>(relaxed = true)
    protected lateinit var owner: UUID

    protected fun setup(dialect: DbDialect) {
        coEvery { cachedMusicBrainzService.getRecording(any(), any()) } returns null
        coEvery { cachedMusicBrainzService.getRelease(any(), any()) } returns null
        coEvery { cachedMusicBrainzService.getArtist(any(), any()) } returns null
        coEvery { cachedMusicBrainzService.getRecording(any()) } returns null
        coEvery { cachedMusicBrainzService.getRelease(any()) } returns null
        coEvery { cachedMusicBrainzService.getArtist(any()) } returns null
        coEvery { imageService.getCoverHashes(any()) } returns emptyMap()
        every { storageService.albumsPath } returns null

        startKoin {
            modules(module {
                includes(entityEventsModule())
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { mockk<MetadataFetchingService>(relaxed = true) }
                single { mockk<RedisSearchService>(relaxed = true) }
                single { cachedMusicBrainzService }
                single { imageService }
                single { pluginManager }
                single { linkResolverService }
                single { storageService }
                single { ReleaseArtistService() }
                single { GenreService() }
                single { LibraryFileDeleter() }
                single { SongService() }
                single { AlbumService() }
                single { ArtistService() }
                single { LibraryMergeService() }
            })
        }

        database = TestDatabase.connect(dialect, "entity_change_library")
        transaction(database) {
            SchemaUtils.create(
                *entityChangeTables,
                SongTitleTagTable,
                AlbumTitleTagTable,
                SongAudioDataTable,
                SongAcoustIdTable,
                SyncedLyricsTable,
                TranscodedSongTable,
                TimecodeTagTable,
                UserSongTable,
                UserCapabilityTable,
                FollowedArtistTable,
                ImageMetadataTable,
                ProviderEnrichmentCheckTable,
                MBReleaseGroupCoverTable,
                RecentReleaseTable,
                ProviderReleaseTable,
                HiddenReleaseTable,
                ArtistSourceRuleTable,
                ReleaseArtistTable,
                RadioChannelTable,
                PodcastShowTable,
                PodcastEpisodeTable,
            )
            owner = UserTable.insertAndGetId {
                it[username] = "owner"
                it[passwordHash] = "hash"
            }.value
        }

        songService = getKoin().get()
        albumService = getKoin().get()
        artistService = getKoin().get()
        libraryMergeService = getKoin().get()
    }

    @AfterEach
    fun tearDown() {
        if (::songService.isInitialized) {
            runBlocking {
                songService.stopService()
                albumService.stopService()
                artistService.stopService()
                libraryMergeService.stopService()
            }
        }
        stopKoin()
        TestDatabase.cleanUp()
    }

    protected fun <T> db(block: () -> T): T = transaction(database) { block() }

    protected fun artist(artistName: String): UUID = db {
        ArtistTable.insertAndGetId { it[name] = artistName }.value
    }

    protected fun album(albumName: String, vararg artists: UUID, tracks: Int = 0, released: String? = null): UUID = db {
        val group = AlbumVersionGroupTable.insertAndGetId { }
        val album = AlbumTable.insertAndGetId {
            it[name] = albumName
            it[songCount] = tracks
            it[releaseDate] = released
            it[versionGroupId] = group
        }.value
        artists.forEachIndexed { index, artist ->
            AlbumArtistTable.insert {
                it[albumId] = album
                it[artistId] = artist
                it[position] = index
            }
        }
        album
    }

    protected fun song(
        album: UUID,
        songTitle: String,
        vararg artists: UUID,
        track: Int = 1,
        location: String = "/music/$songTitle-${UUID.randomUUID()}.flac",
    ): UUID = db {
        val song = SongTable.insertAndGetId {
            it[title] = songTitle
            it[albumId] = EntityID(album, AlbumTable)
            it[trackNumber] = track
            it[filePath] = location
        }.value
        artists.forEachIndexed { index, artist ->
            SongArtistTable.insert {
                it[songId] = song
                it[artistId] = artist
                it[position] = index
            }
        }
        song
    }
}
