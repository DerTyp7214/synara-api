package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.utils.ColorUtils
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

class ColorSearchTest : KoinTest {
    private lateinit var database: Database

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
        database = TestDatabase.connect(dialect, "color_search")
        transaction(database) {
            SchemaUtils.create(*allTables)
        }

        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<ArtistService>(relaxed = true) }
                single { mockk<AlbumService>(relaxed = true) }
                single { mockk<GenreService>(relaxed = true) }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<LibraryMergeService>(relaxed = true) }
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
    fun `byColor should find albums with similar colors`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumService = AlbumService()

        val red = 0xFFFF0000.toInt()
        val (rl, ra, rb) = ColorUtils.rgbToLab(255, 0, 0)

        val blue = 0xFF0000FF.toInt()
        val (bl, ba, bb) = ColorUtils.rgbToLab(0, 0, 255)

        transaction(database) {
            val imgRed = ImageTable.insert {
                it[id] = UUID.randomUUID()
                it[path] = "red.jpg"
                it[imageHash] = "red"
                it[origin] = "test"
            }[ImageTable.id]

            ImageMetadataTable.insert {
                it[imageId] = imgRed
                it[width] = 100
                it[height] = 100
                it[byteSize] = 1000
                it[primaryColor] = red
                it[ImageMetadataTable.red] = 255
                it[ImageMetadataTable.green] = 0
                it[ImageMetadataTable.blue] = 0
                it[luminance] = 0.5
                it[labL] = rl
                it[labA] = ra
                it[labB] = rb
            }

            val imgBlue = ImageTable.insert {
                it[id] = UUID.randomUUID()
                it[path] = "blue.jpg"
                it[imageHash] = "blue"
                it[origin] = "test"
            }[ImageTable.id]

            ImageMetadataTable.insert {
                it[imageId] = imgBlue
                it[width] = 100
                it[height] = 100
                it[byteSize] = 1000
                it[primaryColor] = blue
                it[ImageMetadataTable.red] = 0
                it[ImageMetadataTable.green] = 0
                it[ImageMetadataTable.blue] = 255
                it[luminance] = 0.5
                it[labL] = bl
                it[labA] = ba
                it[labB] = bb
            }

            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Red Album"
                it[cover] = imgRed
                it[songCount] = 10
            }

            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Blue Album"
                it[cover] = imgBlue
                it[songCount] = 10
            }
        }

        val redResult = albumService.byColor(0, 10, red, 5)
        assertEquals(1, redResult.data.size)
        assertEquals("Red Album", redResult.data.first().name)

        val blueResult = albumService.byColor(0, 10, blue, 5)
        assertEquals(1, blueResult.data.size)
        assertEquals("Blue Album", blueResult.data.first().name)

        val greenResult = albumService.byColor(0, 10, 0xFF00FF00.toInt(), 5)
        assertEquals(0, greenResult.data.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byColor should find songs with similar colors`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()
        val userId = UUID.randomUUID()

        val red = 0xFFFF0000.toInt()
        val (rl, ra, rb) = ColorUtils.rgbToLab(255, 0, 0)

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = ""
            }

            val imgRed = ImageTable.insert {
                it[id] = UUID.randomUUID()
                it[path] = "red.jpg"
                it[imageHash] = "red"
                it[origin] = "test"
            }[ImageTable.id]

            ImageMetadataTable.insert {
                it[imageId] = imgRed
                it[width] = 100
                it[height] = 100
                it[byteSize] = 1000
                it[primaryColor] = red
                it[ImageMetadataTable.red] = 255
                it[ImageMetadataTable.green] = 0
                it[ImageMetadataTable.blue] = 0
                it[luminance] = 0.5
                it[labL] = rl
                it[labA] = ra
                it[labB] = rb
            }

            val albumId = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Red Album"
                it[cover] = imgRed
            }[AlbumTable.id]

            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Red Song"
                it[cover] = imgRed
                it[SongTable.albumId] = albumId
            }
        }

        val result = songService.byColor(0, 10, red, 5, true, userId)
        assertEquals(1, result.data.size)
        assertEquals("Red Song", result.data.first().title)
    }
}
