package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
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
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.sqrt

class MosaicSearchTest : KoinTest {
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
        database = TestDatabase.connect(dialect, "mosaic_search")
        transaction(database) {
            SchemaUtils.create(*allTables)
        }

        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { ArtistService() }
                single { AlbumService() }
                single { SongService() }
                single { GenreService() }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<LibraryMergeService>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<CachedMusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { mockk<MetadataFetchingService>(relaxed = true) }
                single { StorageService(get()) }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun createDummyImage(colors: List<Color>): ByteArray {
        val size = ceil(sqrt(colors.size.toDouble())).toInt()
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        colors.forEachIndexed { i, color ->
            image.setRGB(i % size, i / size, color.rgb)
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        return baos.toByteArray()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createSongMosaic should return songs matching image pixels`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val discoveryService = DiscoveryService()
        val userId = UUID.randomUUID()

        val red = Color.RED
        val blue = Color.BLUE
        val (rl, ra, rb) = ColorUtils.rgbToLab(red.red, red.green, red.blue)
        val (bl, ba, bb) = ColorUtils.rgbToLab(blue.red, blue.green, blue.blue)

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = ""
            }

            val imgRed1 = ImageTable.insert {
                it[id] = UUID.randomUUID()
                it[path] = "red1.jpg"
                it[imageHash] = "red1"
                it[origin] = "test"
            }[ImageTable.id]

            ImageMetadataTable.insert {
                it[imageId] = imgRed1
                it[width] = 100
                it[height] = 100
                it[byteSize] = 1000
                it[primaryColor] = red.rgb
                it[ImageMetadataTable.red] = red.red
                it[ImageMetadataTable.green] = red.green
                it[ImageMetadataTable.blue] = red.blue
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
                it[primaryColor] = blue.rgb
                it[ImageMetadataTable.red] = blue.red
                it[ImageMetadataTable.green] = blue.green
                it[ImageMetadataTable.blue] = blue.blue
                it[luminance] = 0.5
                it[labL] = bl
                it[labA] = ba
                it[labB] = bb
            }

            val albumId = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Test Album"
                it[cover] = imgRed1
            }[AlbumTable.id]

            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Red Song"
                it[cover] = imgRed1
                it[SongTable.albumId] = albumId
            }

            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Blue Song"
                it[cover] = imgBlue
                it[SongTable.albumId] = albumId
            }
        }

        val imageBytes = createDummyImage(listOf(Color.RED, Color.BLUE))
        val result = discoveryService.createSongMosaic(imageBytes, 2, 1, 0, 10, 5, userId)

        assertEquals(2, result.data.size)
        assertEquals("Red Song", result.data[0].title)
        assertEquals("Blue Song", result.data[1].title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createSongMosaic should handle pagination with multiple occurrences of same color`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val discoveryService = DiscoveryService()
        val userId = UUID.randomUUID()

        val red = Color.RED
        val (rl, ra, rb) = ColorUtils.rgbToLab(red.red, red.green, red.blue)

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = ""
            }

            repeat(3) { i ->
                val imgId = ImageTable.insert {
                    it[id] = UUID.randomUUID()
                    it[path] = "red$i.jpg"
                    it[imageHash] = "red$i"
                    it[origin] = "test"
                }[ImageTable.id]

                ImageMetadataTable.insert {
                    it[imageId] = imgId
                    it[width] = 100
                    it[height] = 100
                    it[byteSize] = 1000
                    it[primaryColor] = red.rgb
                    it[ImageMetadataTable.red] = red.red
                    it[ImageMetadataTable.green] = red.green
                    it[ImageMetadataTable.blue] = red.blue
                    it[luminance] = 0.5
                    it[labL] = rl
                    it[labA] = ra
                    it[labB] = rb
                }

                val albumId = AlbumTable.insert {
                    it[id] = UUID.randomUUID()
                    it[name] = "Album $i"
                    it[cover] = imgId
                }[AlbumTable.id]

                SongTable.insert {
                    it[id] = UUID.randomUUID()
                    it[title] = "Red Song $i"
                    it[cover] = imgId
                    it[SongTable.albumId] = albumId
                }
            }
        }

        val imageBytes = createDummyImage(listOf(Color.RED, Color.RED, Color.RED))

        val page0 = discoveryService.createSongMosaic(imageBytes, 3, 1, 0, 2, 5, userId)
        assertEquals(2, page0.data.size)
        assertEquals("Red Song 0", page0.data[0].title)
        assertEquals("Red Song 1", page0.data[1].title)

        val page1 = discoveryService.createSongMosaic(imageBytes, 3, 1, 1, 2, 5, userId)
        assertEquals(1, page1.data.size)
        assertEquals("Red Song 2", page1.data[0].title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createAlbumMosaic should return albums matching image pixels`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val discoveryService = DiscoveryService()
        val userId = UUID.randomUUID()

        val red = Color.RED
        val blue = Color.BLUE
        val (rl, ra, rb) = ColorUtils.rgbToLab(red.red, red.green, red.blue)
        val (bl, ba, bb) = ColorUtils.rgbToLab(blue.red, blue.green, blue.blue)

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
                it[primaryColor] = red.rgb
                it[ImageMetadataTable.red] = red.red
                it[ImageMetadataTable.green] = red.green
                it[ImageMetadataTable.blue] = red.blue
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
                it[primaryColor] = blue.rgb
                it[ImageMetadataTable.red] = blue.red
                it[ImageMetadataTable.green] = blue.green
                it[ImageMetadataTable.blue] = blue.blue
                it[luminance] = 0.5
                it[labL] = bl
                it[labA] = ba
                it[labB] = bb
            }

            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Red Album"
                it[cover] = imgRed
            }

            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Blue Album"
                it[cover] = imgBlue
            }
        }

        val imageBytes = createDummyImage(listOf(Color.RED, Color.BLUE))
        val result = discoveryService.createAlbumMosaic(imageBytes, 2, 1, 0, 10, 5, userId)

        assertEquals(2, result.data.size)
        assertEquals("Red Album", result.data[0].name)
        assertEquals("Blue Album", result.data[1].name)
    }
}
