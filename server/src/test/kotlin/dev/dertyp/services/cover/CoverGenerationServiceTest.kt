package dev.dertyp.services.cover

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.CoverGenerationParams
import dev.dertyp.data.CoverStyle
import dev.dertyp.data.CoverTarget
import dev.dertyp.data.CoverTargetType
import dev.dertyp.data.ImageSource
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.ImageService
import dev.dertyp.services.StorageService
import dev.dertyp.services.jobs.JobService
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds

class CoverGenerationServiceTest {
    private lateinit var database: Database
    private lateinit var tempDir: File
    private lateinit var imageService: ImageService
    private lateinit var service: CoverGenerationService
    private val userId = UUID.randomUUID()

    private fun setup(dialect: DbDialect) {
        tempDir = Files.createTempDirectory("cover_test").toFile()
        val storageService = mockk<StorageService>()
        val redisConfig = mockk<RedisCacheProvider.Config>()
        every { storageService.imagesPath } returns File(tempDir, "images").absolutePath
        justRun { storageService.invalidate(any()) }
        every { redisConfig.host } returns "none"
        startKoin { modules(module { single { storageService }; single { redisConfig } }) }

        database = TestDatabase.connect(dialect, "cover_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable, ImageTable, ImageMetadataTable, AlbumTable, ArtistTable, SongTable, SongVariantTable,
                UserPlaylistTable, UserPlaylistSongTable, GenreTable, SongGenreTable, AlbumGenreTable, ArtistGenreTable,
                SongEmbeddingTable, SongAudioDataTable, CollectionTable, CollectionSongTable, CollectionAlbumTable,
                CollectionArtistTable, CollectionPlaylistTable, PlaylistTable, MBReleaseGroupTable, MBReleaseGroupCoverTable,
                RecentReleaseTable, AnimatedImageTable, RadioChannelTable,
            )
            UserTable.insert {
                it[id] = userId
                it[username] = "cover"
                it[passwordHash] = "hash"
            }
        }
        imageService = ImageService(storageService, redisConfig)
        val config = CoverConfig(File(tempDir, "packs").absolutePath, nsfwPacks = false, autoGenerate = true, debounce = 1.seconds)
        jobService = JobService().also { it.pause(CoverGenerationService.JOB_KIND) }
        service = CoverGenerationService(imageService, CoverAssetPackService(config), CoverSourceCollector(), jobService, config)
    }

    private lateinit var jobService: JobService

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        tempDir.deleteRecursively()
    }

    private fun png(color: Color): ByteArray {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = color
        g.fillRect(0, 0, 64, 64)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private suspend fun playlistWithSongs(colors: List<Color>): UUID {
        val covers = colors.map { imageService.createImage(png(it), "test:${it.rgb}") }
        val playlistId = UUID.randomUUID()
        transaction(database) {
            covers.forEachIndexed { index, coverId ->
                ImageMetadataTable.insert {
                    it[imageId] = EntityID(coverId, ImageTable)
                    it[width] = 64; it[height] = 64; it[byteSize] = 1
                    it[primaryColor] = colors[index].rgb
                    it[red] = colors[index].red; it[green] = colors[index].green; it[blue] = colors[index].blue
                    it[luminance] = 0.5
                    it[color1] = colors[index].rgb
                }
            }
            val albumId = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album"
            }[AlbumTable.id]
            UserPlaylistTable.insert {
                it[id] = playlistId
                it[name] = "Late Night Drive"
                it[description] = ""
                it[creator] = EntityID(userId, UserTable)
            }
            covers.forEachIndexed { index, coverId ->
                val songId = UUID.randomUUID()
                SongTable.insert {
                    it[id] = songId
                    it[title] = "Song $index"
                    it[cover] = EntityID(coverId, ImageTable)
                    it[SongTable.albumId] = albumId
                }
                UserPlaylistSongTable.insert {
                    it[UserPlaylistSongTable.playlistId] = playlistId
                    it[UserPlaylistSongTable.songId] = songId
                    it[addedAt] = 1_000L + index
                }
            }
        }
        return playlistId
    }

    private fun playlistRow(id: UUID) = transaction(database) {
        UserPlaylistTable.selectAll().where { UserPlaylistTable.id eq id }.single()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `apply persists a generated cover and marks it generated`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val playlistId = playlistWithSongs(listOf(Color.RED, Color.BLUE, Color.GREEN, Color.ORANGE))
        val target = CoverTarget(CoverTargetType.PLAYLIST, playlistId)

        val imageId = service.apply(target, CoverGenerationParams())

        val row = playlistRow(playlistId)
        assertEquals(imageId, row[UserPlaylistTable.imageId]?.value)
        assertEquals(ImageSource.GENERATED, row[UserPlaylistTable.imageSource])
        assertEquals(CoverStyle.AUTO, row[UserPlaylistTable.coverStyle])
        assertNull(row[UserPlaylistTable.coverSeed])
        val image = imageService.byId(imageId)!!
        assertEquals("generated:playlist:$playlistId", image.origin)
        val bytes = imageService.getImageData(imageId, 0)!!
        assertEquals(1024, ImageIO.read(ByteArrayInputStream(bytes)).width)

        val info = service.coverInfo(target)
        assertEquals(ImageSource.GENERATED, info.source)
        assertEquals(CoverStyle.AUTO, info.style)

        val pinned = service.apply(target, CoverGenerationParams(style = CoverStyle.MOSAIC, seed = 99L))
        val pinnedRow = playlistRow(playlistId)
        assertEquals(pinned, pinnedRow[UserPlaylistTable.imageId]?.value)
        assertEquals(CoverStyle.MOSAIC, pinnedRow[UserPlaylistTable.coverStyle])
        assertEquals(99L, pinnedRow[UserPlaylistTable.coverSeed])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `auto generation skips user covers and regenerates generated ones`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val playlistId = playlistWithSongs(listOf(Color.RED))
        val target = CoverTarget(CoverTargetType.PLAYLIST, playlistId)
        val userImage = imageService.createImage(png(Color.PINK), "custom")
        transaction(database) {
            UserPlaylistTable.update({ UserPlaylistTable.id eq playlistId }) {
                it[imageId] = EntityID(userImage, ImageTable)
                it[imageSource] = ImageSource.USER
            }
        }
        assertNull(service.autoGenerate(target))
        assertEquals(userImage, playlistRow(playlistId)[UserPlaylistTable.imageId]?.value)

        assertTrue(service.reset(target))
        assertNull(playlistRow(playlistId)[UserPlaylistTable.imageId])
        assertFalse(service.reset(target))
        assertEquals(1, jobService.snapshot(CoverGenerationService.JOB_KIND).size)
        assertEquals(target, service.enqueueAuto(target, "x", userId)?.payload)
        assertEquals(1, jobService.snapshot(CoverGenerationService.JOB_KIND).size)

        val first = service.autoGenerate(target)
        assertNotNull(first)
        assertEquals(first, service.autoGenerate(target))

        val extraCover = imageService.createImage(png(Color.YELLOW), "extra")
        transaction(database) {
            val songId = UUID.randomUUID()
            SongTable.insert {
                it[id] = songId
                it[title] = "Extra"
                it[cover] = EntityID(extraCover, ImageTable)
                it[SongTable.albumId] = AlbumTable.selectAll().single()[AlbumTable.id]
            }
            UserPlaylistSongTable.insert {
                it[UserPlaylistSongTable.playlistId] = playlistId
                it[UserPlaylistSongTable.songId] = songId
                it[addedAt] = 5_000L
            }
        }
        val second = service.autoGenerate(target)
        assertNotNull(second)
        assertNotEquals(first, second)
        assertEquals(ImageSource.GENERATED, playlistRow(playlistId)[UserPlaylistTable.imageSource])
        val referenced = imageService.collectReferencedImageIds()
        assertTrue(second in referenced)
        assertFalse(first in referenced)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `same content yields the same seed and preview does not persist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val playlistId = playlistWithSongs(listOf(Color.RED, Color.BLUE))
        val target = CoverTarget(CoverTargetType.PLAYLIST, playlistId)
        val a = service.render(target, CoverGenerationParams())
        val b = service.render(target, CoverGenerationParams())
        assertEquals(a.seed, b.seed)
        assertEquals(CoverStyle.STACKED, a.style)
        assertTrue(a.bytes.contentEquals(b.bytes))
        assertTrue(service.preview(target, CoverGenerationParams(style = CoverStyle.GRADIENT)).isNotEmpty())
        assertNull(playlistRow(playlistId)[UserPlaylistTable.imageId])
        assertEquals(listOf(target), service.missingTargets(userId))
        assertTrue(service.missingTargets(UUID.randomUUID()).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `collections mix artist, album and playlist images`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val playlistId = playlistWithSongs(listOf(Color.RED))
        service.apply(CoverTarget(CoverTargetType.PLAYLIST, playlistId), CoverGenerationParams())
        val artistImage = imageService.createImage(png(Color.CYAN), "artist")
        val collectionId = UUID.randomUUID()
        transaction(database) {
            val artistId = ArtistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Artist"
                it[image] = EntityID(artistImage, ImageTable)
            }[ArtistTable.id]
            CollectionTable.insert {
                it[id] = collectionId
                it[name] = "Everything"
                it[creator] = EntityID(userId, UserTable)
            }
            CollectionArtistTable.insert { it[CollectionArtistTable.collectionId] = collectionId; it[CollectionArtistTable.artistId] = artistId.value }
            CollectionPlaylistTable.insert { it[CollectionPlaylistTable.collectionId] = collectionId; it[CollectionPlaylistTable.playlistId] = playlistId }
        }
        val target = CoverTarget(CoverTargetType.COLLECTION, collectionId)
        val imageId = service.apply(target, CoverGenerationParams(style = CoverStyle.GRID))
        val row = transaction(database) { CollectionTable.selectAll().where { CollectionTable.id eq collectionId }.single() }
        assertEquals(imageId, row[CollectionTable.imageId]?.value)
        assertEquals(ImageSource.GENERATED, row[CollectionTable.imageSource])
        assertEquals("generated:collection:$collectionId", imageService.byId(imageId)!!.origin)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rendering the same target repeatedly is deterministic`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val playlistId = playlistWithSongs(listOf(Color.RED))
        val target = CoverTarget(CoverTargetType.PLAYLIST, playlistId)
        val reference = service.render(target, CoverGenerationParams())
        repeat(6) { iteration ->
            val again = service.render(target, CoverGenerationParams())
            assertEquals(reference.seed, again.seed)
            assertEquals(reference.style, again.style)
            val firstDiff = reference.bytes.indices.firstOrNull { it >= again.bytes.size || reference.bytes[it] != again.bytes[it] }
            assertTrue(firstDiff == null && reference.bytes.size == again.bytes.size, "iteration $iteration differs at byte $firstDiff (sizes ${reference.bytes.size} vs ${again.bytes.size})")
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unknown targets are rejected and origins parse back`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val target = CoverTarget(CoverTargetType.PLAYLIST, UUID.randomUUID())
        assertNull(service.row(target))
        assertNull(service.autoGenerate(target))
        assertEquals(target, CoverGenerationService.parseOrigin(CoverGenerationService.originOf(target)))
        assertNull(CoverGenerationService.parseOrigin("https://example.com/x.jpg"))
    }
}
