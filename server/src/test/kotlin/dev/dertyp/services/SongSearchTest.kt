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

class SongSearchTest : KoinTest {
    private lateinit var database: Database
    private val artistService = mockk<ArtistService>(relaxed = true)
    private val albumService = mockk<AlbumService>(relaxed = true)
    private val imageService = mockk<ImageService>(relaxed = true)
    private val genreService = mockk<GenreService>(relaxed = true)

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
        SongProviderTable,
        *allMusicBrainzTables
    )

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "song_search")
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
                single { genreService }
                single { imageService }
                single { LibraryMergeService() }
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
    fun `rankedSearch should return matching songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()
        val userId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = ""
            }
            val artist1 = ArtistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Daft Punk"
            }
            val album1 = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Discovery"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "One More Time"
                it[albumId] = album1[AlbumTable.id]
            }.also { row ->
                SongArtistTable.insert {
                    it[songId] = row[SongTable.id]
                    it[artistId] = artist1[ArtistTable.id]
                }
            }
        }

        val result = songService.rankedSearch(0, 10, "Time", true, userId)
        assertEquals(1, result.data.size)
        assertEquals("One More Time", result.data.first().title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should rank exact title matches higher`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()
        val userId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = ""
            }
            val album1 = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Test Album"
            }

            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Target Song"
                it[albumId] = album1[AlbumTable.id]
            }
            
            val album2 = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Target Album"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Other Song"
                it[albumId] = album2[AlbumTable.id]
            }
        }

        val result = songService.rankedSearch(0, 10, "Target", true, userId)
        assertEquals(2, result.data.size)
        assertEquals("Target Song", result.data.first().title)
    }
}
