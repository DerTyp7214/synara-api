package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.db.SchemaTables
import dev.dertyp.core.db.referencedUuids
import dev.dertyp.data.PodcastSource
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.util.UUID

class ImageReferenceCleanupTest {
    private lateinit var database: Database
    private lateinit var service: ImageService
    private lateinit var animatedService: AnimatedImageService
    private lateinit var tempDir: File

    private val imageReferenceColumns: Set<Column<*>> = setOf(
        AlbumTable.cover,
        ArtistTable.image,
        SongTable.cover,
        PlaylistTable.imageId,
        UserPlaylistTable.imageId,
        UserTable.profileImage,
        RecentReleaseTable.imageId,
        ProviderReleaseTable.imageId,
        AnimatedImageTable.imageId,
        CollectionTable.imageId,
        RadioChannelTable.imageId,
        MBReleaseGroupCoverTable.imageId,
        PodcastShowTable.imageId,
        PodcastEpisodeTable.imageId,
    )

    private val animatedImageReferenceColumns: Set<Column<*>> = setOf(
        AlbumTable.animatedCover,
        SongTable.animatedCover,
    )

    fun setup(dialect: DbDialect) {
        tempDir = Files.createTempDirectory("image_ref_test").toFile()
        val storageService = mockk<StorageService>()
        val redisConfig = mockk<RedisCacheProvider.Config>()
        every { storageService.imagesPath } returns File(tempDir, "images").absolutePath
        every { storageService.animatedImagesPath } returns File(tempDir, "animated").absolutePath
        justRun { storageService.invalidate(any()) }
        every { redisConfig.host } returns "none"

        startKoin {
            modules(module {
                single { storageService }
                single { redisConfig }
            })
        }

        database = TestDatabase.connect(dialect, "image_ref_test")
        transaction(database) {
            SchemaUtils.create(
                ImageTable, ImageMetadataTable, AlbumTable, ArtistTable, SongTable, SongVariantTable, PlaylistTable,
                UserPlaylistTable, UserTable, MBReleaseGroupTable, MBReleaseGroupCoverTable, RecentReleaseTable,
                ProviderReleaseTable, ProviderLinkTable, RecentReleaseLinkTable, ProviderReleaseLinkTable,
                AnimatedImageTable, CollectionTable, RadioChannelTable, PodcastShowTable, PodcastEpisodeTable
            )
        }

        service = ImageService(storageService, redisConfig)
        animatedService = AnimatedImageService(storageService, redisConfig, service)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        if (::tempDir.isInitialized) tempDir.deleteRecursively()
    }

    @Test
    fun `image references derived from foreign keys match the known referencing columns`() {
        assertEquals(imageReferenceColumns, SchemaTables.referencesTo(ImageTable).toSet())
        assertEquals(animatedImageReferenceColumns, SchemaTables.referencesTo(AnimatedImageTable).toSet())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an image referenced only by one referencing column survives cleanup and an unreferenced one is deleted`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val referenced = imageReferenceColumns.associateWith { column ->
            service.createImage("image of ${column.table.tableName}.${column.name}".toByteArray(), "test")
        }
        val unreferenced = service.createImage("unreferenced".toByteArray(), "test")

        transaction(database) {
            val userId = UserTable.insertAndGetId {
                it[UserTable.username] = "owner"
                it[UserTable.passwordHash] = "hash"
            }
            val artistId = ArtistTable.insertAndGetId { it[ArtistTable.name] = "Artist" }
            val albumId = AlbumTable.insertAndGetId { it[AlbumTable.name] = "Plain Album" }
            val showId = PodcastShowTable.insertAndGetId {
                it[PodcastShowTable.showSource] = PodcastSource.FEED
                it[PodcastShowTable.sourceKey] = "plain-show"
                it[PodcastShowTable.title] = "Plain Show"
            }

            fun image(column: Column<*>) = EntityID(referenced.getValue(column), ImageTable)

            AlbumTable.insert {
                it[AlbumTable.name] = "Album"
                it[AlbumTable.cover] = image(AlbumTable.cover)
            }
            ArtistTable.insert {
                it[ArtistTable.name] = "Imaged Artist"
                it[ArtistTable.image] = image(ArtistTable.image)
            }
            SongTable.insert {
                it[SongTable.title] = "Song"
                it[SongTable.albumId] = albumId
                it[SongTable.cover] = image(SongTable.cover)
            }
            PlaylistTable.insert {
                it[PlaylistTable.name] = "Playlist"
                it[PlaylistTable.imageId] = image(PlaylistTable.imageId)
            }
            UserPlaylistTable.insert {
                it[UserPlaylistTable.name] = "User Playlist"
                it[UserPlaylistTable.description] = ""
                it[UserPlaylistTable.creator] = userId
                it[UserPlaylistTable.imageId] = image(UserPlaylistTable.imageId)
            }
            UserTable.insert {
                it[UserTable.username] = "pictured"
                it[UserTable.passwordHash] = "hash"
                it[UserTable.profileImage] = image(UserTable.profileImage)
            }
            val recentGroup = UUID.randomUUID()
            MBReleaseGroupTable.insert {
                it[MBReleaseGroupTable.id] = recentGroup
                it[MBReleaseGroupTable.title] = "Recent"
            }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = recentGroup
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Recent"
                it[RecentReleaseTable.imageId] = image(RecentReleaseTable.imageId)
            }
            ProviderReleaseTable.insert {
                it[ProviderReleaseTable.provider] = "provider"
                it[ProviderReleaseTable.externalId] = "release"
                it[ProviderReleaseTable.artistId] = artistId
                it[ProviderReleaseTable.title] = "Provider Release"
                it[ProviderReleaseTable.imageId] = image(ProviderReleaseTable.imageId)
            }
            AnimatedImageTable.insert {
                it[AnimatedImageTable.path] = "animated.mp4"
                it[AnimatedImageTable.contentHash] = "animated"
                it[AnimatedImageTable.origin] = "test"
                it[AnimatedImageTable.imageId] = image(AnimatedImageTable.imageId)
            }
            CollectionTable.insert {
                it[CollectionTable.name] = "Collection"
                it[CollectionTable.creator] = userId
                it[CollectionTable.imageId] = image(CollectionTable.imageId)
            }
            RadioChannelTable.insert {
                it[RadioChannelTable.name] = "Radio"
                it[RadioChannelTable.imageId] = image(RadioChannelTable.imageId)
            }
            val coverGroup = UUID.randomUUID()
            MBReleaseGroupTable.insert {
                it[MBReleaseGroupTable.id] = coverGroup
                it[MBReleaseGroupTable.title] = "Cover"
            }
            MBReleaseGroupCoverTable.insert {
                it[MBReleaseGroupCoverTable.releaseGroupId] = coverGroup
                it[MBReleaseGroupCoverTable.imageId] = image(MBReleaseGroupCoverTable.imageId)
            }
            PodcastShowTable.insert {
                it[PodcastShowTable.showSource] = PodcastSource.FEED
                it[PodcastShowTable.sourceKey] = "imaged-show"
                it[PodcastShowTable.title] = "Imaged Show"
                it[PodcastShowTable.imageId] = image(PodcastShowTable.imageId)
            }
            PodcastEpisodeTable.insert {
                it[PodcastEpisodeTable.showId] = showId
                it[PodcastEpisodeTable.guid] = "episode"
                it[PodcastEpisodeTable.guidKey] = "episode"
                it[PodcastEpisodeTable.title] = "Episode"
                it[PodcastEpisodeTable.publishedAt] = 0L
                it[PodcastEpisodeTable.imageId] = image(PodcastEpisodeTable.imageId)
            }
        }

        transaction(database) {
            val referencedPerColumn = imageReferenceColumns.associateWith { it.referencedUuids().toSet() }
            referenced.forEach { (column, imageId) ->
                val holders = referencedPerColumn.filterValues { imageId in it }.keys
                assertEquals(setOf(column), holders, "${column.table.tableName}.${column.name} must be the only reference of its image")
            }
        }

        assertEquals(referenced.values.toSet(), service.collectReferencedImageIds())

        val deleted = service.deleteUnreferencedImages()

        assertEquals(1, deleted)
        assertNull(service.byId(unreferenced))
        referenced.forEach { (column, imageId) ->
            assertNotNull(service.byId(imageId), "image referenced by ${column.table.tableName}.${column.name} was deleted")
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an animated image referenced only by one referencing column survives cleanup and an unreferenced one is deleted`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        fun insertAnimated(hash: String) = transaction(database) {
            AnimatedImageTable.insertAndGetId {
                it[AnimatedImageTable.path] = "$hash.mp4"
                it[AnimatedImageTable.contentHash] = hash
                it[AnimatedImageTable.origin] = "test"
            }.value
        }

        val albumAnimated = insertAnimated("album")
        val songAnimated = insertAnimated("song")
        val unreferenced = insertAnimated("unreferenced")

        transaction(database) {
            AlbumTable.insert {
                it[AlbumTable.name] = "Album"
                it[AlbumTable.animatedCover] = EntityID(albumAnimated, AnimatedImageTable)
            }
            val plainAlbum = AlbumTable.insertAndGetId { it[AlbumTable.name] = "Plain" }
            SongTable.insert {
                it[SongTable.title] = "Song"
                it[SongTable.albumId] = plainAlbum
                it[SongTable.animatedCover] = EntityID(songAnimated, AnimatedImageTable)
            }
        }

        val deleted = animatedService.deleteUnreferencedAnimatedImages()

        assertEquals(1, deleted)
        assertNull(animatedService.byId(unreferenced))
        assertNotNull(animatedService.byId(albumAnimated))
        assertNotNull(animatedService.byId(songAnimated))
    }
}
