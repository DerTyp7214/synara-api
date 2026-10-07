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
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
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

        database = TestDatabase.connect(
            dialect, "image_entity_change",
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

    private fun stored(location: String): UUID = transaction(database) {
        ImageTable.insertAndGetId {
            it[path] = location
            it[imageHash] = UUID.randomUUID().toString()
            it[origin] = "test"
        }.value
    }

    private fun locations() = transaction(database) {
        ImageTable.selectAll().associate { it[ImageTable.id].value to it[ImageTable.path] }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving images replaces the prefix at the start and records nothing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val direct = stored("old/images/direct.jpg")
        val repeated = stored("old/images/sub/old/images/repeated.jpg")
        val exact = stored("old/images")
        val longer = stored("old/images2/longer.jpg")
        val elsewhere = stored("other/old/images/elsewhere.jpg")
        val absolute = stored("/old/images/absolute.jpg")
        covered(direct)
        pictured(direct)

        assertEquals(4, service.moveImages("old/images", "new/pictures/"))

        assertEquals(
            mapOf(
                direct to "new/pictures//direct.jpg",
                repeated to "new/pictures//sub/old/images/repeated.jpg",
                exact to "new/pictures/",
                longer to "new/pictures/2/longer.jpg",
                elsewhere to "other/old/images/elsewhere.jpg",
                absolute to "/old/images/absolute.jpg",
            ),
            locations()
        )
        assertEquals(emptySet<Any>(), recordedChanges(database))

        assertEquals(6, service.moveImages("", "/mnt/"))
        assertEquals(
            mapOf(
                direct to "/mnt/new/pictures//direct.jpg",
                repeated to "/mnt/new/pictures//sub/old/images/repeated.jpg",
                exact to "/mnt/new/pictures/",
                longer to "/mnt/new/pictures/2/longer.jpg",
                elsewhere to "/mnt/other/old/images/elsewhere.jpg",
                absolute to "/mnt//old/images/absolute.jpg",
            ),
            locations()
        )
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving images matches case, percent, underscore and backslash the way the database does`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val lower = stored("/old/lower.jpg")
        val upper = stored("/OLD/sub/old/upper.jpg")
        val literal = stored("/100%_lib/50%_off.jpg")
        val wildcard = stored("/100xylib/wildcard.jpg")
        val later = stored("/100abclib/100%_lib/later.jpg")
        val windows = stored("C:\\covers\\windows.jpg")
        val slashless = stored("C:covers/slashless.jpg")
        val sqlite = dialect == DbDialect.SQLITE

        assertEquals(if (sqlite) 2 else 1, service.moveImages("/old", "/new"))
        assertEquals(3, service.moveImages("/100%_lib", "/lib_%"))
        assertEquals(1, service.moveImages("C:\\covers", "D:\\art"))

        assertEquals(
            mapOf(
                lower to "/new/lower.jpg",
                upper to if (sqlite) "/OLD/sub/new/upper.jpg" else "/OLD/sub/old/upper.jpg",
                literal to "/lib_%/50%_off.jpg",
                wildcard to "/100xylib/wildcard.jpg",
                later to "/100abclib/lib_%/later.jpg",
                windows to if (sqlite) "D:\\art\\windows.jpg" else "C:\\covers\\windows.jpg",
                slashless to "C:covers/slashless.jpg",
            ),
            locations()
        )
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving images keeps paths outside the basic latin letters intact`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val oldPrefix = "/Bilder 😀🎧/日本 cafe\u0301"
        val newPrefix = "/Neu ß 🎵"
        val umlauts = stored("$oldPrefix/Ünï çödé – Öl.jpg")
        val emoji = stored("$oldPrefix/🎶 😀🎧 100% _live_.jpg")
        val spaces = stored("$oldPrefix  two  spaces .jpg")
        val composed = stored("/Bilder 😀🎧/日本 café/composed.jpg")
        val shorter = stored("/Bilder 😀/日本 cafe\u0301/shorter.jpg")

        assertEquals(3, service.moveImages(oldPrefix, newPrefix))

        assertEquals(
            mapOf(
                umlauts to "$newPrefix/Ünï çödé – Öl.jpg",
                emoji to "$newPrefix/🎶 😀🎧 100% _live_.jpg",
                spaces to "$newPrefix  two  spaces .jpg",
                composed to "/Bilder 😀🎧/日本 café/composed.jpg",
                shorter to "/Bilder 😀/日本 cafe\u0301/shorter.jpg",
            ),
            locations()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving images without a match changes nothing`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        assertEquals(0, service.moveImages("old/images", "new/images"))
        assertEquals(emptyMap<UUID, String>(), locations())

        val kept = stored("covers/kept.jpg")

        assertEquals(0, service.moveImages("old/images", "new/images"))
        assertEquals(mapOf(kept to "covers/kept.jpg"), locations())
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }
}
