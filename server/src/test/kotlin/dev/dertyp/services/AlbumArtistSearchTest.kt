package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class AlbumArtistSearchTest : KoinTest {
    private lateinit var database: Database
    private val artistService = mockk<ArtistService>(relaxed = true)
    private val albumService = mockk<AlbumService>(relaxed = true)
    private val songService = mockk<SongService>(relaxed = true)
    private val imageService = mockk<ImageService>(relaxed = true)
    private val genreService = mockk<GenreService>(relaxed = true)
    private val libraryMergeService = mockk<LibraryMergeService>(relaxed = true)

    private val allTables = arrayOf(
        ArtistTable, AlbumTable, SongTable, SongVariantTable, SongArtistTable, 
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
        database = TestDatabase.connect(dialect, "album_artist_search")
        transaction(database) {
            SchemaUtils.create(*allTables)
        }

        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<CachedMusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { artistService }
                single { albumService }
                single { songService }
                single { genreService }
                single { imageService }
                single { libraryMergeService }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `AlbumService rankedSearch should return matching albums`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumServiceReal = AlbumService()

        transaction(database) {
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Random Access Memories"
                it[songCount] = 10 
            }
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Discovery"
                it[songCount] = 10
            }
        }

        val result = albumServiceReal.rankedSearch(0, 10, "Random")
        assertEquals(1, result.data.size)
        assertEquals("Random Access Memories", result.data.first().name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ArtistService rankedSearch should return matching artists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistServiceReal = ArtistService()

        transaction(database) {
            ArtistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Daft Punk"
            }
            ArtistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Justice"
            }
        }

        val result = artistServiceReal.rankedSearch(0, 10, "Daft")
        assertEquals(1, result.data.size)
        assertEquals("Daft Punk", result.data.first().name)
    }
}
