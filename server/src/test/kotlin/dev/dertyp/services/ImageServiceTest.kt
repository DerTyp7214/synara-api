package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.MBReleaseGroupTable
import dev.dertyp.db.PlaylistTable
import dev.dertyp.db.RecentReleaseTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserPlaylistTable
import dev.dertyp.db.UserTable
import dev.dertyp.plugins.RedisCacheProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
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
            SchemaUtils.create(ImageTable, AlbumTable, ArtistTable, SongTable, PlaylistTable, UserPlaylistTable, UserTable, MBReleaseGroupTable, RecentReleaseTable)
        }

        service = ImageService(storageService, redisConfig)
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
}
