package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.isImage
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.utils.ColorUtils
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
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
import java.util.Random
import java.util.UUID
import javax.imageio.ImageIO

class ImageServiceTest {
    private lateinit var database: Database
    private lateinit var service: ImageService
    private lateinit var storageService: StorageService
    private lateinit var redisConfig: RedisCacheProvider.Config
    private lateinit var tempDir: File

    fun setup(dialect: DbDialect) {
        tempDir = Files.createTempDirectory("image_test").toFile()
        
        storageService = mockk<StorageService>()
        redisConfig = mockk<RedisCacheProvider.Config>()
        
        every { storageService.imagesPath } returns tempDir.absolutePath
        justRun { storageService.invalidate(any()) }
        every { redisConfig.host } returns "none"

        startKoin {
            modules(module {
                single { storageService }
                single { redisConfig }
            })
        }

        database = TestDatabase.connect(dialect, "image_test")
        transaction(database) {
            SchemaUtils.create(ImageTable, ImageMetadataTable, AlbumTable, ArtistTable, SongTable, SongVariantTable, PlaylistTable, UserPlaylistTable, UserTable, MBReleaseGroupTable, MBReleaseGroupCoverTable, RecentReleaseTable, ProviderReleaseTable, ProviderLinkTable, RecentReleaseLinkTable, ProviderReleaseLinkTable, AnimatedImageTable, CollectionTable, RadioChannelTable, PodcastShowTable, PodcastEpisodeTable)
        }

        service = ImageService(storageService, redisConfig)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return image with blurHash and metadata`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val imageId = UUID.randomUUID()
        transaction(database) {
            ImageTable.insert {
                it[id] = imageId
                it[path] = "test.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
                it[blurHash] = "LKO2?V%2S1?bM69GZ~v._38_9Gv."
            }
            ImageMetadataTable.insert {
                it[ImageMetadataTable.imageId] = EntityID(imageId, ImageTable)
                it[width] = 100
                it[height] = 200
                it[byteSize] = 1024L
                it[primaryColor] = 0xFF0000
                it[red] = 255
                it[green] = 0
                it[blue] = 0
                it[luminance] = 0.5
                it[color1] = 0xFF0000
            }
        }

        val image = service.byId(imageId)
        assertNotNull(image)
        assertEquals("LKO2?V%2S1?bM69GZ~v._38_9Gv.", image?.blurHash)
        assertEquals(100, image?.width)
        assertEquals(200, image?.height)
        assertEquals(1024L, image?.byteSize)
        assertEquals(0xFF0000, image?.primaryColor)
        assertEquals(0.5, image?.luminance)
        assertEquals(listOf(0xFF0000), image?.palette)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `analyzeImage should calculate BlurHash and metadata`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        
        val bufferedImage = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val g = bufferedImage.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, 100, 100)
        g.dispose()
        
        val baos = ByteArrayOutputStream()
        ImageIO.write(bufferedImage, "png", baos)
        val data = baos.toByteArray()
        
        val id = service.createImage(data, "test_analysis")
        
        service.analyzeImage(id)
        
        val image = service.byId(id)
        assertNotNull(image)
        assertNotNull(image?.blurHash)
        assertEquals(100, image?.width)
        assertEquals(100, image?.height)
        assertEquals(data.size.toLong(), image?.byteSize)
        
        assertEquals(0xFFFF0000.toInt(), image?.primaryColor)
        
        assertEquals(0.2126, image?.luminance!!, 0.01)
        
        assertNotNull(image.palette)
        assertTrue(image.palette?.contains(0xFFFF0000.toInt()) == true)

        transaction(database) {
            val metadata = ImageMetadataTable.selectAll().where { ImageMetadataTable.imageId eq id }.single()
            assertNotNull(metadata[ImageMetadataTable.labL])
            assertNotNull(metadata[ImageMetadataTable.labA])
            assertNotNull(metadata[ImageMetadataTable.labB])
            assertNotNull(metadata[ImageMetadataTable.hue])
            assertNotNull(metadata[ImageMetadataTable.saturation])
            assertNotNull(metadata[ImageMetadataTable.lightness])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getUnanalyzedImageIds should return images without metadata`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val id1 = service.createImage(byteArrayOf(1, 2, 3, 4), "test1")
        val id2 = service.createImage(byteArrayOf(5, 6, 7, 8), "test2")
        
        transaction(database) {
            ImageMetadataTable.insert {
                it[imageId] = EntityID(id1, ImageTable)
                it[width] = 10
                it[height] = 10
                it[byteSize] = 4
                it[primaryColor] = 0
                it[red] = 0
                it[green] = 0
                it[blue] = 0
                it[luminance] = 0.0
            }
        }
        
        val unanalyzed = service.getUnanalyzedImageIds()
        assertEquals(1, unanalyzed.size)
        assertEquals(id2, unanalyzed[0])
    }

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

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `analyzeImage marks custom-origin image with missing file as unrecoverable`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val id = service.createImage(redPngBytes(), "profile")
        File(service.byId(id)!!.path).delete()

        service.analyzeImage(id)

        transaction(database) {
            val row = ImageTable.selectAll().where { ImageTable.id eq id }.single()
            assertTrue(row[ImageTable.analysisUnrecoverable])
            assertNotNull(row[ImageTable.lastAnalysisAttempt])
            assertEquals(0L, ImageMetadataTable.selectAll().where { ImageMetadataTable.imageId eq id }.count())
        }
        assertFalse(service.getUnanalyzedImageIds().contains(id))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `analyzeImage marks audio-origin image with missing source as retryable not unrecoverable`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val id = service.createImage(redPngBytes(), "/nonexistent/path/song.flac")
        File(service.byId(id)!!.path).delete()

        service.analyzeImage(id)

        transaction(database) {
            val row = ImageTable.selectAll().where { ImageTable.id eq id }.single()
            assertFalse(row[ImageTable.analysisUnrecoverable])
            assertNotNull(row[ImageTable.lastAnalysisAttempt])
            assertEquals(0L, ImageMetadataTable.selectAll().where { ImageMetadataTable.imageId eq id }.count())
        }
        assertFalse(service.getUnanalyzedImageIds().contains(id))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getUnanalyzedImageIds throttles recently attempted images but re-includes them after the retry interval`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val recentId = UUID.randomUUID()
        val staleId = UUID.randomUUID()
        val now = System.currentTimeMillis()
        transaction(database) {
            ImageTable.insert {
                it[id] = recentId
                it[path] = "recent.jpg"
                it[imageHash] = "recent"
                it[origin] = "https://example.com/a.jpg"
                it[lastAnalysisAttempt] = now
            }
            ImageTable.insert {
                it[id] = staleId
                it[path] = "stale.jpg"
                it[imageHash] = "stale"
                it[origin] = "https://example.com/b.jpg"
                it[lastAnalysisAttempt] = now - 8L * 24 * 60 * 60 * 1000
            }
        }

        val result = service.getUnanalyzedImageIds()
        assertFalse(result.contains(recentId))
        assertTrue(result.contains(staleId))
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        if (::tempDir.isInitialized) {
            tempDir.deleteRecursively()
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createImage should save file and return id`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = byteArrayOf(1, 2, 3, 4)
        val id = service.createImage(data, "test")
        
        assertNotNull(id)
        val image = service.byId(id)
        assertNotNull(image)
        assertEquals("test", image?.origin)
        
        val file = File(image!!.path)
        assertEquals(true, file.exists())
        assertEquals(data.toList(), file.readBytes().toList())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byHash should return existing image`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val data = byteArrayOf(5, 6, 7, 8)
        val id = service.createImage(data, "origin")
        val image = service.byId(id)
        
        val found = service.byHash(image!!.imageHash)
        assertEquals(id, found?.id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteUnreferencedImages should not delete images referenced in RecentReleaseTable`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        
        val data = byteArrayOf(9, 10, 11, 12)
        val imageId = service.createImage(data, "release_origin")
        
        transaction(database) {
            val aId = ArtistTable.insertAndGetId {
                it[ArtistTable.name] = "Artist"
            }
            val relGroupId = UUID.randomUUID()
            MBReleaseGroupTable.insert {
                it[id] = relGroupId
                it[title] = "Title"
            }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = relGroupId
                it[RecentReleaseTable.artistId] = aId
                it[RecentReleaseTable.title] = "Title"
                it[RecentReleaseTable.imageId] = EntityID(imageId, ImageTable)
            }
        }
        
        val deletedCount = service.deleteUnreferencedImages()
        assertEquals(0, deletedCount)
        assertNotNull(service.byId(imageId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteUnreferencedImages should delete unreferenced images`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        
        val data = byteArrayOf(13, 14, 15, 16)
        val imageId = service.createImage(data, "unreferenced")
        
        val deletedCount = service.deleteUnreferencedImages()
        assertEquals(1, deletedCount)
        assertEquals(null, service.byId(imageId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `collectReferencedImageIds should include images from all referencing tables`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val albumImage = service.createImage(byteArrayOf(20, 21), "album")
        val releaseImage = service.createImage(byteArrayOf(22, 23), "release")
        val unreferencedImage = service.createImage(byteArrayOf(24, 25), "unreferenced")

        transaction(database) {
            AlbumTable.insert { it[name] = "Album"; it[cover] = EntityID(albumImage, ImageTable) }
            val aId = ArtistTable.insertAndGetId { it[name] = "Artist" }
            val relGroupId = UUID.randomUUID()
            MBReleaseGroupTable.insert { it[id] = relGroupId; it[title] = "Title" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = relGroupId
                it[RecentReleaseTable.artistId] = aId
                it[RecentReleaseTable.title] = "Title"
                it[RecentReleaseTable.imageId] = EntityID(releaseImage, ImageTable)
            }
        }

        val referenced = service.collectReferencedImageIds()
        assertTrue(albumImage in referenced)
        assertTrue(releaseImage in referenced)
        assertFalse(unreferencedImage in referenced)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteImagesByIds should delete only the given images with their files`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val toDelete = service.createImage(byteArrayOf(30, 31), "delete_me")
        val toKeep = service.createImage(byteArrayOf(32, 33), "keep_me")
        val deleteFile = File(service.byId(toDelete)!!.path)
        val keepFile = File(service.byId(toKeep)!!.path)
        assertTrue(deleteFile.exists())

        val deleted = service.deleteImagesByIds(listOf(toDelete))

        assertEquals(1, deleted)
        assertNull(service.byId(toDelete))
        assertNotNull(service.byId(toKeep))
        assertFalse(deleteFile.exists())
        assertTrue(keepFile.exists())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moveImages should handle large number of images`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val imageCount = 80000
        val oldPath = "old/images"
        val newPath = "new/images"

        transaction(database) {
            ImageTable.batchInsert((1..imageCount)) { i ->
                this[ImageTable.id] = UUID.randomUUID()
                this[ImageTable.path] = "$oldPath/image_$i.jpg"
                this[ImageTable.imageHash] = "hash_$i"
                this[ImageTable.origin] = "test"
            }
        }

        val moved = service.moveImages(oldPath, newPath)
        assertEquals(imageCount, moved)

        transaction(database) {
            val count = ImageTable.selectAll().where { ImageTable.path like "$newPath%" }.count()
            assertEquals(imageCount.toLong(), count)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `generateMosaicImage should return a 16k image via flow`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val red = Color.RED
        val (rl, ra, rb) = ColorUtils.rgbToLab(red.red, red.green, red.blue)

        transaction(database) {
            val imgId = ImageTable.insert {
                it[id] = UUID.randomUUID()
                it[path] = "red.jpg"
                it[imageHash] = "red"
                it[origin] = "test"
            }[ImageTable.id]

            val fullPath = File(storageService.imagesPath, "red.jpg")
            fullPath.parentFile.mkdirs()
            val img = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.RED
            g.fillRect(0, 0, 10, 10)
            g.dispose()
            ImageIO.write(img, "jpg", fullPath)

            ImageMetadataTable.insert {
                it[imageId] = imgId
                it[width] = 10
                it[height] = 10
                it[byteSize] = 100
                it[primaryColor] = red.rgb
                it[ImageMetadataTable.red] = red.red
                it[ImageMetadataTable.green] = red.green
                it[ImageMetadataTable.blue] = red.blue
                it[luminance] = 0.5
                it[labL] = rl
                it[labA] = ra
                it[labB] = rb
            }
        }

        val inputImg = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val ig = inputImg.createGraphics()
        ig.color = Color.RED
        ig.fillRect(0, 0, 2, 2)
        ig.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(inputImg, "png", baos)

        val results = service.generateMosaicImage(baos.toByteArray(), 2, 2, 1024).toList()
        assertTrue(results.isNotEmpty())
        
        val assembledImage = results.mapNotNull { it.chunk }.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        assertTrue(assembledImage.isNotEmpty())

        val resultImg = ImageIO.read(ByteArrayInputStream(assembledImage))
        assertEquals(1024, resultImg.width)
        assertEquals(1024, resultImg.height)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `generateMosaicImage should handle chunking and progress correctly`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val red = Color.RED
        val (rl, ra, rb) = ColorUtils.rgbToLab(red.red, red.green, red.blue)
        transaction(database) {
            repeat(30) { i ->
                val imgId = ImageTable.insert {
                    it[id] = UUID.randomUUID()
                    it[path] = "red$i.jpg"
                    it[imageHash] = "red$i"
                    it[origin] = "test"
                }[ImageTable.id]

                val fullPath = File(storageService.imagesPath, "red$i.jpg")
                fullPath.parentFile.mkdirs()
                val img = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                g.color = Color.RED
                g.fillRect(0, 0, 10, 10)
                g.dispose()
                ImageIO.write(img, "jpg", fullPath)

                ImageMetadataTable.insert {
                    it[imageId] = imgId
                    it[width] = 10
                    it[height] = 10
                    it[byteSize] = 100
                    it[primaryColor] = red.rgb
                    it[ImageMetadataTable.red] = red.red
                    it[ImageMetadataTable.green] = red.green
                    it[ImageMetadataTable.blue] = red.blue
                    it[luminance] = 0.5
                    it[labL] = rl
                    it[labA] = ra
                    it[labB] = rb
                }
            }
        }

        val inputImg = BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB)
        val ig = inputImg.createGraphics()
        val random = Random()
        for (x in 0 until 50) {
            for (y in 0 until 50) {
                ig.color = Color(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                ig.drawRect(x, y, 1, 1)
            }
        }
        ig.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(inputImg, "png", baos)

        val results = service.generateMosaicImage(baos.toByteArray(), 50, 50, 8192).toList()
        
        assertTrue(results.any { it.progress == 0.0 }, "Should have starting progress")
        assertTrue(results.any { it.progress in 0.1..0.45 }, "Should have loading progress")
        assertTrue(results.any { it.progress in 0.45..0.85 }, "Should have rendering progress")
        assertTrue(results.any { it.progress in 0.85..0.95 }, "Should have encoding progress")
        assertEquals(1.0, results.last().progress, "Should finish at 1.0")

        var lastProgress = -1.0
        results.forEach {
            assertTrue(it.progress >= lastProgress, "Progress should be monotonic: ${it.progress} vs $lastProgress")
            lastProgress = it.progress
        }

        val chunks = results.filter { it.chunk != null }
        assertTrue(chunks.size > 1, "Should have multiple chunks for 8k image (got ${chunks.size})")
        
        assertTrue(results.last().isLast, "Last response should have isLast = true")
        
        chunks.forEach { 
            assertTrue(it.chunk!!.size <= 1024 * 1024, "Chunk size should not exceed 1MB")
        }

        val assembled = chunks.mapNotNull { it.chunk }.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        val resultImg = ImageIO.read(ByteArrayInputStream(assembled))
        assertEquals(8192, resultImg.width)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getImageData returns null for a stored non-image file`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val id = service.createImage(
            "<!doctype html><html>not found</html>".toByteArray(),
            "https://coverartarchive.org/release-group/x/front"
        )

        assertNull(service.getImageData(id, 0))
        assertNull(service.getImageData(id, 100))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getImageData returns image bytes for a real image`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val id = service.createImage(redPngBytes(), "png")

        val full = service.getImageData(id, 0)
        assertNotNull(full)
        assertTrue(full!!.isImage())

        val thumbnail = service.getImageData(id, 2)
        assertNotNull(thumbnail)
        assertTrue(thumbnail!!.isImage())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `purgeNonImageFiles removes non-image files with matching origins and unlinks references`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val bogusId = service.createImage(
            "<!doctype html><html>not found</html>".toByteArray(),
            "https://coverartarchive.org/release-group/x/front"
        )
        val goodId = service.createImage(redPngBytes(), "https://coverartarchive.org/release-group/y/front")
        val profileId = service.createImage("<html>profile error</html>".toByteArray(), "profile")

        val bogusFile = File(service.byId(bogusId)!!.path)
        val goodFile = File(service.byId(goodId)!!.path)
        val profileFile = File(service.byId(profileId)!!.path)

        val bogusGroupId = UUID.randomUUID()
        val goodGroupId = UUID.randomUUID()

        transaction(database) {
            val albumId = AlbumTable.insertAndGetId {
                it[name] = "Album"
                it[cover] = EntityID(bogusId, ImageTable)
            }
            val artistId = ArtistTable.insertAndGetId {
                it[name] = "Artist"
                it[image] = EntityID(bogusId, ImageTable)
            }
            SongTable.insert {
                it[title] = "Song"
                it[SongTable.albumId] = albumId
                it[cover] = EntityID(bogusId, ImageTable)
            }

            MBReleaseGroupTable.insert { it[id] = bogusGroupId; it[title] = "Bogus" }
            RecentReleaseTable.insert {
                it[releaseId] = bogusGroupId
                it[RecentReleaseTable.artistId] = artistId
                it[title] = "Bogus"
                it[imageId] = EntityID(bogusId, ImageTable)
                it[lastImageFetch] = 1000L
            }
            MBReleaseGroupCoverTable.insert {
                it[releaseGroupId] = bogusGroupId
                it[imageId] = EntityID(bogusId, ImageTable)
                it[lastFetch] = 1000L
            }

            MBReleaseGroupTable.insert { it[id] = goodGroupId; it[title] = "Good" }
            RecentReleaseTable.insert {
                it[releaseId] = goodGroupId
                it[RecentReleaseTable.artistId] = artistId
                it[title] = "Good"
                it[imageId] = EntityID(goodId, ImageTable)
            }
        }

        val result = service.purgeNonImageFiles(listOf("https://coverartarchive.org/"))

        assertNull(service.byId(bogusId))
        assertFalse(bogusFile.exists())
        assertNotNull(service.byId(goodId))
        assertTrue(goodFile.exists())
        assertNotNull(service.byId(profileId))
        assertTrue(profileFile.exists())

        transaction(database) {
            val bogusRelease = RecentReleaseTable.selectAll()
                .where { RecentReleaseTable.releaseId eq bogusGroupId }
                .single()
            assertNull(bogusRelease[RecentReleaseTable.imageId])
            assertNull(bogusRelease[RecentReleaseTable.lastImageFetch])

            val cover = MBReleaseGroupCoverTable.selectAll()
                .where { MBReleaseGroupCoverTable.releaseGroupId eq bogusGroupId }
                .single()
            assertNull(cover[MBReleaseGroupCoverTable.imageId])
            assertEquals(0L, cover[MBReleaseGroupCoverTable.lastFetch])

            assertNull(AlbumTable.selectAll().single()[AlbumTable.cover])
            assertNull(ArtistTable.selectAll().single()[ArtistTable.image])
            assertNull(SongTable.selectAll().single()[SongTable.cover])

            val goodRelease = RecentReleaseTable.selectAll()
                .where { RecentReleaseTable.releaseId eq goodGroupId }
                .single()
            assertEquals(goodId, goodRelease[RecentReleaseTable.imageId]?.value)
        }

        assertEquals(2, result.scanned)
        assertEquals(1, result.bogus)
        assertEquals(1, result.deleted)
        assertTrue(result.unlinked >= 5, "Expected at least 5 unlinked references, got ${result.unlinked}")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `purgeNonImageFiles is a no-op on rerun and skips missing files`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val bogusId = service.createImage(
            "<!doctype html><html>not found</html>".toByteArray(),
            "https://coverartarchive.org/release-group/x/front"
        )
        val missingId = service.createImage(
            "<html>gone</html>".toByteArray(),
            "https://coverartarchive.org/release-group/y/front"
        )
        assertTrue(File(service.byId(missingId)!!.path).delete())

        val first = service.purgeNonImageFiles(listOf("https://coverartarchive.org/"))
        assertEquals(2, first.scanned)
        assertEquals(1, first.bogus)
        assertEquals(1, first.deleted)
        assertNull(service.byId(bogusId))
        assertNotNull(service.byId(missingId))

        val second = service.purgeNonImageFiles(listOf("https://coverartarchive.org/"))
        assertEquals(1, second.scanned)
        assertEquals(0, second.bogus)
        assertEquals(0, second.unlinked)
        assertEquals(0, second.deleted)
        assertNotNull(service.byId(missingId))
    }
}
