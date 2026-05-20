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
        SongProviderTable, AlbumProviderTable,
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
                it[username] = "user_${UUID.randomUUID()}"
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
                it[username] = "user_${UUID.randomUUID()}"
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

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `searchByLyrics should return matching songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()
        val userId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user_${UUID.randomUUID()}"
                it[passwordHash] = ""
            }
            val albumId = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album"
            }[AlbumTable.id]

            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Song with lyrics"
                it[lyrics] = "I'm a barbie girl, in a barbie world"
                it[SongTable.albumId] = albumId
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Other song"
                it[lyrics] = "Life in plastic, it's fantastic"
                it[SongTable.albumId] = albumId
            }
        }

        val result = songService.searchByLyrics(0, 10, "barbie", true, userId)
        assertEquals(1, result.data.size)
        assertEquals("Song with lyrics", result.data.first().title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `searchByLyrics should find matches in synced lyrics`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()
        val userId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user_${UUID.randomUUID()}"
                it[passwordHash] = ""
            }
            val albumId = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album"
            }[AlbumTable.id]

            val songId = UUID.randomUUID()
            SongTable.insert {
                it[id] = songId
                it[title] = "AI Transcribed"
                it[SongTable.albumId] = albumId
            }
            SyncedLyricsTable.insert {
                it[SyncedLyricsTable.songId] = songId
                it[SyncedLyricsTable.rawLyrics] = "This was transcribed by whisper"
            }
        }

        val result = songService.searchByLyrics(0, 10, "transcribed", true, userId)
        assertEquals(1, result.data.size)
        assertEquals("AI Transcribed", result.data.first().title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `searchByLyrics should support negative keywords`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()
        val userId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user_${UUID.randomUUID()}"
                it[passwordHash] = ""
            }
            val albumId = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album"
            }[AlbumTable.id]

            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Good Match"
                it[lyrics] = "hello world"
                it[SongTable.albumId] = albumId
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Bad Match"
                it[lyrics] = "hello darkness"
                it[SongTable.albumId] = albumId
            }
        }

        val result = songService.searchByLyrics(0, 10, "hello -darkness", true, userId)
        assertEquals(1, result.data.size)
        assertEquals("Good Match", result.data.first().title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `searchByLyrics should respect explicit filter`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()
        val userId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user_${UUID.randomUUID()}"
                it[passwordHash] = ""
            }
            val albumRow = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album"
            }
            val albumId = albumRow[AlbumTable.id]
            
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Explicit Song"
                it[lyrics] = "curse words"
                it[explicit] = true
                it[SongTable.albumId] = albumId
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Clean Song"
                it[lyrics] = "nice words"
                it[explicit] = false
                it[SongTable.albumId] = albumId
            }
        }

        val explicitResult = songService.searchByLyrics(0, 10, "words", true, userId)
        assertEquals(2, explicitResult.data.size)

        val cleanResult = songService.searchByLyrics(0, 10, "words", false, userId)
        assertEquals(1, cleanResult.data.size)
        assertEquals("Clean Song", cleanResult.data.first().title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `searchByLyrics should rank matches in synced lyrics higher than plain lyrics`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()
        val userId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user_${UUID.randomUUID()}"
                it[passwordHash] = ""
            }
            val albumId = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album"
            }[AlbumTable.id]

            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Plain Match"
                it[lyrics] = "The quick brown fox"
                it[SongTable.albumId] = albumId
            }

            val song2Id = UUID.randomUUID()
            SongTable.insert {
                it[id] = song2Id
                it[title] = "Synced Match"
                it[SongTable.albumId] = albumId
            }
            SyncedLyricsTable.insert {
                it[SyncedLyricsTable.songId] = song2Id
                it[SyncedLyricsTable.rawLyrics] = "The quick brown fox"
            }
        }

        val result = songService.searchByLyrics(0, 10, "quick brown", true, userId)
        assertEquals(2, result.data.size)
        assertEquals("Synced Match", result.data.first().title)
    }
}
