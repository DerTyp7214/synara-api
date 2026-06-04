package dev.dertyp.core

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.User
import dev.dertyp.db.*
import dev.dertyp.services.SongService
import dev.dertyp.services.metadata.IMetadataService
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
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
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class FlowTest {
    private lateinit var database: Database
    private val songService = SongService()
    private val user = User(UUID.randomUUID(), "test", passwordHash = "hash")

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
        database = TestDatabase.connect(dialect, "flow_test")
        transaction(database) {
            SchemaUtils.create(*allTables)
        }
        
        startKoin {
            modules(module {
                single { mockk<dev.dertyp.services.StorageService>(relaxed = true) }
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
    fun `filterExisting should filter tracks by ISRC`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val isrc = "USAT20300184"
        
        transaction(database) {
            val albumId = AlbumTable.insert { it[name] = "Album" }[AlbumTable.id]
            SongTable.insert {
                it[title] = "Existing Song"
                it[this.albumId] = albumId
                it[this.isrc] = isrc
                it[filePath] = "path"
            }
        }

        val tracks = listOf(
            IMetadataService.Track(
                id = "new-id",
                title = "New Song with same ISRC",
                duration = 3.minutes,
                images = emptyList(),
                isrc = isrc
            ),
            IMetadataService.Track(
                id = "another-id",
                title = "Truly New Song",
                duration = 3.minutes,
                images = emptyList(),
                isrc = "DIFFERENT123"
            )
        )

        val filtered = tracks.asFlow().filterExisting(songService, user).toList().flatten()

        assertEquals(1, filtered.size)
        assertEquals("another-id", filtered[0].id)
    }
}
