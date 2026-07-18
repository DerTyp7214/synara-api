package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.ImageService
import dev.dertyp.services.ScheduledTaskLogService
import dev.dertyp.services.StorageService
import io.mockk.every
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
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.Path

class UnlinkUnfollowedRecentReleaseImagesTest : KoinTest {
    private lateinit var database: Database
    private lateinit var imageService: ImageService
    private lateinit var tempDir: File

    private fun setup(dialect: DbDialect) {
        tempDir = Files.createTempDirectory("unlink_unfollowed_test").toFile()

        val storageService = mockk<StorageService>()
        val redisConfig = mockk<RedisCacheProvider.Config>()
        every { storageService.imagesPath } returns tempDir.absolutePath
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

        database = TestDatabase.connect(dialect, "unlink_unfollowed_test")
        transaction(database) {
            SchemaUtils.create(
                ImageTable,
                ImageMetadataTable,
                AlbumTable,
                ArtistTable,
                SongTable,
                PlaylistTable,
                UserPlaylistTable,
                UserTable,
                MBReleaseGroupTable,
                MBReleaseGroupCoverTable,
                FollowedArtistTable,
                RecentReleaseTable,
                AnimatedImageTable,
                CollectionTable,
                RadioChannelTable,
                ScheduledTaskLogTable,
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

    private suspend fun createStoredImage(content: String, origin: String): Pair<UUID, File> {
        val id = imageService.createImage(content.toByteArray(), origin)
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

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unlinks unfollowed releases and deletes their unreferenced images`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val followedArtist = UUID.randomUUID()
        val unfollowedArtist = UUID.randomUUID()
        val unfollowedRelease = UUID.randomUUID()
        val sharedRelease = UUID.randomUUID()
        val followedRelease = UUID.randomUUID()

        val (unfollowedImage, unfollowedFile) = createStoredImage("unfollowed cover", "https://coverartarchive.org/release-group/$unfollowedRelease/front")
        val (sharedImage, sharedFile) = createStoredImage("shared cover", "https://coverartarchive.org/release-group/$sharedRelease/front")
        val (followedImage, followedFile) = createStoredImage("followed cover", "https://coverartarchive.org/release-group/$followedRelease/front")

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = followedArtist; it[name] = "Followed" }
            ArtistTable.insert { it[id] = unfollowedArtist; it[name] = "Unfollowed" }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = followedArtist }
            AlbumTable.insert { it[name] = "Album"; it[cover] = EntityID(sharedImage, ImageTable) }
        }

        insertRecentRelease(unfollowedRelease, unfollowedArtist, unfollowedImage)
        insertRecentRelease(sharedRelease, unfollowedArtist, sharedImage)
        insertRecentRelease(followedRelease, followedArtist, followedImage)

        assertTrue(unfollowedFile.exists())

        UnlinkUnfollowedRecentReleaseImages().migrate()

        transaction(database) {
            val rows = RecentReleaseTable.selectAll().associate {
                it[RecentReleaseTable.releaseId].value to (it[RecentReleaseTable.imageId]?.value to it[RecentReleaseTable.lastImageFetch])
            }
            assertNull(rows[unfollowedRelease]!!.first)
            assertNull(rows[unfollowedRelease]!!.second)
            assertNull(rows[sharedRelease]!!.first)
            assertEquals(followedImage, rows[followedRelease]!!.first)
            assertEquals(1000L, rows[followedRelease]!!.second)

            val remainingImages = ImageTable.selectAll().map { it[ImageTable.id].value }.toSet()
            assertFalse(unfollowedImage in remainingImages)
            assertTrue(sharedImage in remainingImages)
            assertTrue(followedImage in remainingImages)
        }

        assertFalse(unfollowedFile.exists())
        assertTrue(sharedFile.exists())
        assertTrue(followedFile.exists())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `re-running the migration is a no-op`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val unfollowedArtist = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val (imageId, file) = createStoredImage("cover", "https://coverartarchive.org/release-group/$releaseId/front")

        transaction(database) {
            ArtistTable.insert { it[id] = unfollowedArtist; it[name] = "Unfollowed" }
        }
        insertRecentRelease(releaseId, unfollowedArtist, imageId)

        UnlinkUnfollowedRecentReleaseImages().migrate()
        UnlinkUnfollowedRecentReleaseImages().migrate()

        transaction(database) {
            val row = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseId }.single()
            assertNull(row[RecentReleaseTable.imageId])
            assertEquals(0, ImageTable.selectAll().count().toInt())
        }
        assertFalse(file.exists())
    }
}
