package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
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
            SchemaUtils.create(ImageTable, AlbumTable, ArtistTable, SongTable, PlaylistTable, UserPlaylistTable, UserTable)
        }

        service = ImageService()
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
}
