package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class MetadataFetchingServiceTest : KoinTest {
    private lateinit var database: Database
    private val imageService = mockk<ImageService>(relaxed = true)
    private val musicBrainzService = mockk<MusicBrainzService>(relaxed = true)
    private val environment = mockk<ApplicationEnvironment>(relaxed = true)

    private val allTables = arrayOf(
        ArtistTable, AlbumTable, SongTable, SongArtistTable, 
        SongMusicBrainzTable, SongAudioDataTable, ImageTable, GenreTable,
        UserTable, AlbumMusicBrainzTable, ArtistMusicBrainzTable,
        ArtistAliasTable, ArtistMemberTable, AlbumArtistTable,
        PlaylistTable, UserSongTable, UserPlaylistTable,
        SongGenreTable, ArtistGenreTable, AlbumGenreTable,
        PlaylistSongTable, UserPlaylistSongTable,
        SyncedLyricsTable, ImageMetadataTable, RecentReleaseTable,
        FollowedArtistTable, TranscodedSongTable, CustomMigrationTable,
        ScheduledTaskLogTable, ArtistSplitAliasTable, SyncServiceTable,
        *allMusicBrainzTables
    )

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "metadata_fetch")
        transaction(database) {
            SchemaUtils.create(*allTables)
        }

        startKoin {
            modules(module {
                single { environment }
                single { imageService }
                single { GenreService() }
                single { musicBrainzService }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `refreshArtistMetadata should handle provider failure gracefully`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val fetchingService = MetadataFetchingService(environment)

        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()

        transaction(database) {
            MBArtistTable.insert {
                it[id] = EntityID(mbId, MBArtistTable)
                it[name] = "Failure Artist"
                it[sortName] = "Failure Artist"
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Failure Artist"
            }
            ArtistMusicBrainzTable.insert {
                it[ArtistMusicBrainzTable.artistId] = artistId
                it[musicBrainzId] = mbId
            }
        }

        val mockTdbService = mockk<MetadataService>(relaxed = true)
        MetadataService.register(IMetadataService.MetadataType.theAudioDB, mockTdbService)
        
        coEvery { mockTdbService.getArtistByMbId(mbId, any()) } throws RuntimeException("TDB is down")

        assertDoesNotThrow {
            runBlocking {
                fetchingService.refreshArtistMetadata(artistId, IMetadataService.MetadataType.theAudioDB)
            }
        }

        coVerify { mockTdbService.getArtistByMbId(mbId, any()) }
    }
}
