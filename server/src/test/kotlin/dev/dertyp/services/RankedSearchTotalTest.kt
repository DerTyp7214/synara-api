package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
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

class RankedSearchTotalTest : KoinTest {
    private lateinit var database: Database
    private val redisSearchService = mockk<RedisSearchService>(relaxed = true)

    private val allTables = arrayOf(
        ArtistTable, AlbumTable, SongTable, SongVariantTable, SongTitleTagTable, AlbumTitleTagTable, SongArtistTable,
        SongMusicBrainzTable, SongAudioDataTable, ImageTable, GenreTable,
        UserTable, AlbumMusicBrainzTable, ArtistMusicBrainzTable,
        ArtistAliasTable, ArtistMemberTable, AlbumArtistTable,
        PlaylistTable, UserSongTable, TimecodeTagTable, UserPlaylistTable,
        SongGenreTable, ArtistGenreTable, AlbumGenreTable,
        PlaylistSongTable, UserPlaylistSongTable,
        SyncedLyricsTable, ImageMetadataTable, RecentReleaseTable,
        FollowedArtistTable, TranscodedSongTable, CustomMigrationTable,
        ScheduledTaskLogTable, ArtistSplitAliasTable, SyncServiceTable,
        SongProviderTable, AlbumProviderTable,
        *allMusicBrainzTables
    )

    private fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "ranked_search_total", *allTables)

        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<CachedMusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { mockk<ArtistService>(relaxed = true) }
                single { mockk<AlbumService>(relaxed = true) }
                single { mockk<GenreService>(relaxed = true) }
                single { mockk<ImageService>(relaxed = true) }
                single { LibraryMergeService() }
                single { LibraryFileDeleter() }
                single { redisSearchService }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertSongs(userId: UUID, titles: List<String>): List<UUID> = transaction(database) {
        UserTable.insert {
            it[id] = userId
            it[username] = "searcher"
            it[passwordHash] = ""
        }
        val album = AlbumTable.insert {
            it[id] = UUID.randomUUID()
            it[name] = "Album"
        }
        titles.map { songTitle ->
            val songId = UUID.randomUUID()
            SongTable.insert {
                it[id] = songId
                it[title] = songTitle
                it[albumId] = album[AlbumTable.id]
            }
            songId
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the redis total and order are used and do not leak into the next search`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val (first, second, third) = insertSongs(userId, listOf("Echo One", "Echo Two", "Echo Three"))
        val songService = SongService()

        every { redisSearchService.isEnabled() } returns true
        every { redisSearchService.search("song", "Echo", any(), any()) } returns
                RedisSearchService.SearchResult(listOf(third, first), 42)

        val redisResult = songService.rankedSearch(0, 10, "Echo", true, userId)
        assertEquals(42, redisResult.total)
        assertEquals(listOf(third, first), redisResult.data.map { it.id })

        every { redisSearchService.isEnabled() } returns false

        val databaseResult = songService.rankedSearch(0, 10, "Echo", true, userId)
        assertEquals(3, databaseResult.total)
        assertEquals(setOf(first, second, third), databaseResult.data.map { it.id }.toSet())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an empty redis result falls back to the database count`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        insertSongs(userId, listOf("Echo One", "Echo Two"))
        val songService = SongService()

        every { redisSearchService.isEnabled() } returns true
        every { redisSearchService.search(any(), any(), any(), any()) } returns
                RedisSearchService.SearchResult(emptyList(), 17)

        val result = songService.rankedSearch(0, 10, "Echo", true, userId)
        assertEquals(2, result.total)
        assertEquals(2, result.data.size)
    }
}
