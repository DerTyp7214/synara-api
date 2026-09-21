package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.ImageService
import dev.dertyp.services.ScheduledTaskLogService
import dev.dertyp.services.StorageService
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.Path

class PurgeNonImageCoverFilesTest : KoinTest {
    private lateinit var database: Database
    private lateinit var imageService: ImageService
    private lateinit var tempDir: File

    private fun setup(dialect: DbDialect) {
        tempDir = Files.createTempDirectory("purge_non_image_test").toFile()

        val storageService = mockk<StorageService>()
        val redisConfig = mockk<RedisCacheProvider.Config>()
        every { storageService.imagesPath } returns tempDir.absolutePath
        justRun { storageService.invalidate(any()) }
        every { redisConfig.host } returns "none"

        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)

        startKoin {
            modules(module {
                single { logService }
                single { storageService }
                single { redisConfig }
                single { ImageService(get(), get()) }
            })
        }

        database = TestDatabase.connect(dialect, "purge_non_image_test")
        transaction(database) {
            SchemaUtils.create(
                ImageTable,
                ImageMetadataTable,
                AlbumTable,
                ArtistTable,
                SongTable, SongVariantTable,
                PlaylistTable,
                UserPlaylistTable,
                UserTable,
                MBReleaseGroupTable,
                MBReleaseGroupCoverTable,
                FollowedArtistTable,
                RecentReleaseTable,
                ProviderReleaseTable,
                ProviderLinkTable,
                RecentReleaseLinkTable,
                ProviderReleaseLinkTable,
                AnimatedImageTable,
                CollectionTable,
                RadioChannelTable,
                ScheduledTaskLogTable,
                PodcastShowTable,
                PodcastEpisodeTable,
            )
        }

        imageService = ImageService(storageService, redisConfig)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        tempDir.deleteRecursively()
    }

    private fun htmlNotFoundBytes(): ByteArray =
        "<!doctype html><html lang=en><title>404 Not Found</title></html>".toByteArray()

    private fun redPngBytes(): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, 4, 4)
        g.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        return baos.toByteArray()
    }

    private suspend fun createStoredImage(content: ByteArray, origin: String): Pair<UUID, File> {
        val id = imageService.createImage(content, origin)
        val path = transaction(database) {
            ImageTable.select(ImageTable.path).where { ImageTable.id eq id }.single()[ImageTable.path]
        }
        return id to Path(tempDir.absolutePath, path).toFile()
    }

    private fun insertRecentRelease(releaseId: UUID, artistId: UUID, imageId: UUID?) {
        transaction(database) {
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Rel $releaseId" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Rel $releaseId"
                it[RecentReleaseTable.imageId] = imageId?.let { id -> EntityID(id, ImageTable) }
                it[RecentReleaseTable.lastImageFetch] = 1000L
            }
        }
    }

    private fun insertReleaseGroupCover(releaseGroupId: UUID, imageId: UUID?) {
        transaction(database) {
            MBReleaseGroupCoverTable.insert {
                it[MBReleaseGroupCoverTable.releaseGroupId] = releaseGroupId
                it[MBReleaseGroupCoverTable.imageId] = imageId?.let { id -> EntityID(id, ImageTable) }
                it[MBReleaseGroupCoverTable.lastFetch] = 1000L
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `migration deletes non-image cover files and unlinks the releases`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artist = UUID.randomUUID()
        val htmlReleaseGroup = UUID.randomUUID()
        val pngReleaseGroup = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artist; it[name] = "Artist" }
        }

        val (htmlImage, htmlFile) = createStoredImage(
            htmlNotFoundBytes(),
            "https://coverartarchive.org/release-group/$htmlReleaseGroup/front"
        )
        val (pngImage, pngFile) = createStoredImage(
            redPngBytes(),
            "https://coverartarchive.org/release-group/$pngReleaseGroup/front"
        )
        val (customImage, customFile) = createStoredImage("<html>custom upload</html>".toByteArray(), "custom")

        insertRecentRelease(htmlReleaseGroup, artist, htmlImage)
        insertReleaseGroupCover(htmlReleaseGroup, htmlImage)
        insertRecentRelease(pngReleaseGroup, artist, pngImage)

        assertTrue(htmlFile.exists())
        assertTrue(pngFile.exists())
        assertTrue(customFile.exists())

        PurgeNonImageCoverFiles().migrate()

        transaction(database) {
            val remainingImages = ImageTable.selectAll().map { it[ImageTable.id].value }.toSet()
            assertFalse(htmlImage in remainingImages)
            assertTrue(pngImage in remainingImages)
            assertTrue(customImage in remainingImages)

            val htmlRelease = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq htmlReleaseGroup }.single()
            assertNull(htmlRelease[RecentReleaseTable.imageId])
            assertNull(htmlRelease[RecentReleaseTable.lastImageFetch])

            val pngRelease = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq pngReleaseGroup }.single()
            assertEquals(pngImage, pngRelease[RecentReleaseTable.imageId]?.value)
            assertEquals(1000L, pngRelease[RecentReleaseTable.lastImageFetch])

            val cover = MBReleaseGroupCoverTable.selectAll()
                .where { MBReleaseGroupCoverTable.releaseGroupId eq htmlReleaseGroup }.single()
            assertNull(cover[MBReleaseGroupCoverTable.imageId])
            assertEquals(0L, cover[MBReleaseGroupCoverTable.lastFetch])
        }

        assertFalse(htmlFile.exists())
        assertTrue(pngFile.exists())
        assertTrue(customFile.exists())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `re-running the migration is a no-op`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artist = UUID.randomUUID()
        val releaseGroup = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artist; it[name] = "Artist" }
        }

        val (image, file) = createStoredImage(
            htmlNotFoundBytes(),
            "https://coverartarchive.org/release-group/$releaseGroup/front"
        )
        insertRecentRelease(releaseGroup, artist, image)
        insertReleaseGroupCover(releaseGroup, image)

        PurgeNonImageCoverFiles().migrate()

        val imageCountAfterFirst = transaction(database) { ImageTable.selectAll().count() }
        val existsAfterFirst = file.exists()

        PurgeNonImageCoverFiles().migrate()

        val imageCountAfterSecond = transaction(database) { ImageTable.selectAll().count() }
        val existsAfterSecond = file.exists()

        assertEquals(imageCountAfterFirst, imageCountAfterSecond)
        assertEquals(existsAfterFirst, existsAfterSecond)
        assertFalse(existsAfterSecond)

        transaction(database) {
            val release = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseGroup }.single()
            assertNull(release[RecentReleaseTable.imageId])
            assertNull(release[RecentReleaseTable.lastImageFetch])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `migration skips rows whose file is missing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artist = UUID.randomUUID()
        val releaseGroup = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artist; it[name] = "Artist" }
        }

        val (image, file) = createStoredImage(
            htmlNotFoundBytes(),
            "https://coverartarchive.org/release-group/$releaseGroup/front"
        )
        insertRecentRelease(releaseGroup, artist, image)

        assertTrue(file.delete())

        PurgeNonImageCoverFiles().migrate()

        transaction(database) {
            assertTrue(ImageTable.selectAll().any { it[ImageTable.id].value == image })

            val release = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseGroup }.single()
            assertEquals(image, release[RecentReleaseTable.imageId]?.value)
            assertEquals(1000L, release[RecentReleaseTable.lastImageFetch])
        }
    }
}
