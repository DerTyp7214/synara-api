package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.EntityType
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.testing.RecordedChange
import dev.dertyp.testing.clearRecordedChanges
import dev.dertyp.testing.entityChangeTables
import dev.dertyp.testing.entityEventsModule
import dev.dertyp.testing.recordedChanges
import dev.dertyp.testing.updated
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
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
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO

class ImageEntityChangeTest {
    private lateinit var database: Database
    private lateinit var service: ImageService
    private lateinit var tempDir: File

    private data class Covered(val album: UUID, val artist: UUID, val song: UUID, val plain: UUID)

    private fun setup(dialect: DbDialect) {
        tempDir = Files.createTempDirectory("image_entity_change").toFile()
        val storageService = mockk<StorageService>()
        val redisConfig = mockk<RedisCacheProvider.Config>()
        every { storageService.imagesPath } returns tempDir.absolutePath
        justRun { storageService.invalidate(any()) }
        every { redisConfig.host } returns "none"

        startKoin {
            modules(module {
                includes(entityEventsModule())
                single { storageService }
                single { redisConfig }
            })
        }

        database = TestDatabase.connect(dialect, "image_entity_change")
        transaction(database) {
            SchemaUtils.create(
                *entityChangeTables,
                ImageMetadataTable,
                MBReleaseGroupCoverTable,
                RecentReleaseTable,
                ProviderReleaseTable,
                ProviderLinkTable,
                RecentReleaseLinkTable,
                ProviderReleaseLinkTable,
                RadioChannelTable,
                PodcastShowTable,
                PodcastEpisodeTable,
            )
        }
        service = ImageService(storageService, redisConfig)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        if (::tempDir.isInitialized) tempDir.deleteRecursively()
    }

    private fun redPng(): ByteArray {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, 16, 16)
        graphics.dispose()
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)
        return bytes.toByteArray()
    }

    private fun covered(picture: UUID): Covered = transaction(database) {
        val album = AlbumTable.insertAndGetId {
            it[name] = "Covered"
            it[cover] = EntityID(picture, ImageTable)
        }
        val plainAlbum = AlbumTable.insertAndGetId { it[name] = "Plain" }
        val artist = ArtistTable.insertAndGetId {
            it[name] = "Pictured"
            it[image] = EntityID(picture, ImageTable)
        }
        val song = SongTable.insertAndGetId {
            it[title] = "Covered Song"
            it[albumId] = album
            it[cover] = EntityID(picture, ImageTable)
        }
        val plain = SongTable.insertAndGetId {
            it[title] = "Plain Song"
            it[albumId] = plainAlbum
        }
        Covered(album.value, artist.value, song.value, plain.value)
    }

    private fun updatedOf(entities: Covered) = setOf(
        updated(EntityType.ALBUM, entities.album),
        updated(EntityType.ARTIST, entities.artist),
        updated(EntityType.SONG, entities.song),
    )

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a new blur hash records the entities shown with that image`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val picture = service.createImage(redPng(), "test")
        val entities = covered(picture)
        assertEquals(emptySet<Any>(), recordedChanges(database))

        service.analyzeImage(picture)

        assertNotNull(service.byId(picture)!!.blurHash)
        assertEquals(updatedOf(entities), recordedChanges(database))
        clearRecordedChanges(database)

        service.analyzeImage(picture)

        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    private fun pictured(picture: UUID): Set<RecordedChange> = transaction(database) {
        val author = UserTable.insertAndGetId {
            it[username] = "creator-${UUID.randomUUID()}"
            it[passwordHash] = "hash"
        }
        val global = PlaylistTable.insertAndGetId {
            it[name] = "Global"
            it[imageId] = EntityID(picture, ImageTable)
        }
        PlaylistTable.insertAndGetId { it[name] = "Plain" }
        val userPlaylist = UserPlaylistTable.insertAndGetId {
            it[name] = "Mix"
            it[description] = ""
            it[UserPlaylistTable.creator] = author
            it[imageId] = EntityID(picture, ImageTable)
        }
        val collection = CollectionTable.insertAndGetId {
            it[name] = "Shelf"
            it[CollectionTable.creator] = author
            it[imageId] = EntityID(picture, ImageTable)
        }
        CollectionTable.insertAndGetId {
            it[name] = "Plain"
            it[CollectionTable.creator] = author
        }
        setOf(
            updated(EntityType.PLAYLIST, global.value),
            updated(EntityType.USER_PLAYLIST, userPlaylist.value),
            updated(EntityType.COLLECTION, collection.value),
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a new blur hash records the playlists and collections shown with that image`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val picture = service.createImage(redPng(), "test")
            val shown = pictured(picture)

            service.analyzeImage(picture)

            assertEquals(shown, recordedChanges(database))
            clearRecordedChanges(database)

            service.analyzeImage(picture)

            assertEquals(emptySet<Any>(), recordedChanges(database))
        }
}
