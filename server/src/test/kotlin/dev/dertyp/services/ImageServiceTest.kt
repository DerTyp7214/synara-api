package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.util.UUID

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
        every { redisConfig.host } returns "none"

        startKoin {
            modules(module {
                single { storageService }
                single { redisConfig }
            })
        }

        database = TestDatabase.connect(dialect, "image_test")
        transaction(database) {
            SchemaUtils.create(ImageTable, ImageMetadataTable, AlbumTable, ArtistTable, SongTable, PlaylistTable, UserPlaylistTable, UserTable, MBReleaseGroupTable, RecentReleaseTable)
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
}
