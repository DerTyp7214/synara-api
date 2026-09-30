package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.db.SchemaTables
import dev.dertyp.data.PodcastSource
import dev.dertyp.db.*
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.metadata.TidalService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class LibraryMergeImageReferencesTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: LibraryMergeService

    private fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>() }
                single { mockk<SongService>() }
                single { mockk<AlbumService>() }
                single { mockk<PluginManager>() }
                single { mockk<TidalService>() }
                single { LibraryFileDeleter() }
                single { mockk<RedisSearchService>(relaxed = true) }
            })
        }

        database = TestDatabase.connect(dialect, "merge_image_refs_test")
        transaction(database) {
            SchemaUtils.create(
                ArtistTable, AlbumTable, SongTable, SongVariantTable, ImageTable, ImageMetadataTable, PlaylistTable,
                UserTable, UserPlaylistTable, UserPlaylistSongTable, PlaylistSongTable,
                SongArtistTable, AlbumArtistTable, AlbumMusicBrainzTable, SongMusicBrainzTable,
                TranscodedSongTable, UserSongTable, SongProviderTable, AlbumProviderTable,
                CollectionTable, CollectionSongTable, CollectionAlbumTable, CollectionArtistTable, CollectionPlaylistTable,
                MBReleaseGroupTable, MBReleaseGroupCoverTable, RecentReleaseTable, ProviderReleaseTable,
                ProviderLinkTable, RecentReleaseLinkTable, ProviderReleaseLinkTable,
                AnimatedImageTable, RadioChannelTable, PodcastShowTable, PodcastEpisodeTable
            )
        }
        service = LibraryMergeService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertImage(path: String): EntityID<UUID> = ImageTable.insertAndGetId {
        it[ImageTable.path] = path
        it[ImageTable.imageHash] = "same-hash"
        it[ImageTable.origin] = "test"
    }

    private fun insertReferences(image: EntityID<UUID>, suffix: String) {
        val userId = UserTable.insertAndGetId {
            it[UserTable.username] = "user-$suffix"
            it[UserTable.passwordHash] = "hash"
            it[UserTable.profileImage] = image
        }
        val artistId = ArtistTable.insertAndGetId {
            it[ArtistTable.name] = "Artist $suffix"
            it[ArtistTable.image] = image
        }
        val albumId = AlbumTable.insertAndGetId {
            it[AlbumTable.name] = if (suffix == "a") "Northern Lights" else "Deep Ocean Tales"
            it[AlbumTable.cover] = image
        }
        SongTable.insert {
            it[SongTable.title] = "Song $suffix"
            it[SongTable.albumId] = albumId
            it[SongTable.filePath] = "song-$suffix"
            it[SongTable.cover] = image
        }
        PlaylistTable.insert {
            it[PlaylistTable.name] = "Playlist $suffix"
            it[PlaylistTable.imageId] = image
        }
        UserPlaylistTable.insert {
            it[UserPlaylistTable.name] = "User Playlist $suffix"
            it[UserPlaylistTable.description] = ""
            it[UserPlaylistTable.creator] = userId
            it[UserPlaylistTable.imageId] = image
        }
        val recentGroup = UUID.randomUUID()
        MBReleaseGroupTable.insert {
            it[MBReleaseGroupTable.id] = recentGroup
            it[MBReleaseGroupTable.title] = "Recent $suffix"
        }
        RecentReleaseTable.insert {
            it[RecentReleaseTable.releaseId] = recentGroup
            it[RecentReleaseTable.artistId] = artistId
            it[RecentReleaseTable.title] = "Recent $suffix"
            it[RecentReleaseTable.imageId] = image
        }
        ProviderReleaseTable.insert {
            it[ProviderReleaseTable.provider] = "provider"
            it[ProviderReleaseTable.externalId] = "release-$suffix"
            it[ProviderReleaseTable.artistId] = artistId
            it[ProviderReleaseTable.title] = "Provider Release $suffix"
            it[ProviderReleaseTable.imageId] = image
        }
        AnimatedImageTable.insert {
            it[AnimatedImageTable.path] = "animated-$suffix.mp4"
            it[AnimatedImageTable.contentHash] = "animated-$suffix"
            it[AnimatedImageTable.origin] = "test"
            it[AnimatedImageTable.imageId] = image
        }
        CollectionTable.insert {
            it[CollectionTable.name] = "Collection $suffix"
            it[CollectionTable.creator] = userId
            it[CollectionTable.imageId] = image
        }
        RadioChannelTable.insert {
            it[RadioChannelTable.name] = "Radio $suffix"
            it[RadioChannelTable.imageId] = image
        }
        val coverGroup = UUID.randomUUID()
        MBReleaseGroupTable.insert {
            it[MBReleaseGroupTable.id] = coverGroup
            it[MBReleaseGroupTable.title] = "Cover $suffix"
        }
        MBReleaseGroupCoverTable.insert {
            it[MBReleaseGroupCoverTable.releaseGroupId] = coverGroup
            it[MBReleaseGroupCoverTable.imageId] = image
        }
        val showId = PodcastShowTable.insertAndGetId {
            it[PodcastShowTable.showSource] = PodcastSource.FEED
            it[PodcastShowTable.sourceKey] = "show-$suffix"
            it[PodcastShowTable.title] = "Show $suffix"
            it[PodcastShowTable.imageId] = image
        }
        PodcastEpisodeTable.insert {
            it[PodcastEpisodeTable.showId] = showId
            it[PodcastEpisodeTable.guid] = "episode-$suffix"
            it[PodcastEpisodeTable.guidKey] = "episode-$suffix"
            it[PodcastEpisodeTable.title] = "Episode $suffix"
            it[PodcastEpisodeTable.publishedAt] = 0L
            it[PodcastEpisodeTable.imageId] = image
        }
    }

    private fun Column<*>.values(): List<Any?> = table.select(this).map { it[this] }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `merging duplicate images repoints every referencing column to the kept image`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val images = transaction(database) {
            val first = insertImage("first")
            val second = insertImage("second")
            insertReferences(first, "a")
            insertReferences(second, "b")
            setOf(first.value, second.value)
        }

        val result = service.mergeDuplicates()

        assertEquals(1, result["imagesMerged"])
        transaction(database) {
            val kept = ImageTable.select(ImageTable.id).map { it[ImageTable.id].value }
            assertEquals(1, kept.size)
            val keptId = kept.single()
            assertEquals(true, keptId in images)

            val columns = SchemaTables.referencesTo(ImageTable)
            assertEquals(14, columns.size)
            columns.forEach { column ->
                val name = "${column.table.tableName}.${column.name}"
                val values = column.values().map { (it as EntityID<*>?)?.value }
                assertEquals(listOf(keptId, keptId), values, "$name must point at the kept image")
                assertEquals(0L, column.table.selectAll().where { column.isNull() }.count(), "$name was cleared")
            }
        }
    }
}
