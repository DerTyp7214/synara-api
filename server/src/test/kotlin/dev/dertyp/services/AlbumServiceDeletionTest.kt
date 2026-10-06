package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.testing.entityChangeTables
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.File
import java.util.UUID

class AlbumServiceDeletionTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: AlbumService
    private val musicBrainzService = mockk<MusicBrainzService>(relaxed = true)
    private val storageService = mockk<StorageService>(relaxed = true)
    private val libraryMergeService = mockk<LibraryMergeService>(relaxed = true)
    private val redisSearchService = mockk<RedisSearchService>(relaxed = true)

    @TempDir
    lateinit var tempDir: File

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { EntityChangeRecorder() }
                single { musicBrainzService }
                single { MusicBrainzCacheService() }
                single { storageService }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<MetadataFetchingService>(relaxed = true) }
                single { ArtistService() }
                single { GenreService() }
                single { CachedMusicBrainzService(get(), get()) }
                single { libraryMergeService }
                single { redisSearchService }
                single { LibraryFileDeleter() }
            })
        }

        database = TestDatabase.connect(dialect, "album_deletion_test")
        transaction(database) {
            SchemaUtils.create(
                *entityChangeTables,
                UserTable,
                AlbumTable,
                AlbumArtistTable,
                ArtistTable,
                ArtistMemberTable,
                ArtistMusicBrainzTable,
                ArtistAliasTable,
                FollowedArtistTable,
                AlbumMusicBrainzTable,
                ImageTable,
                ImageMetadataTable,
                AnimatedImageTable,
                SongTable, SongVariantTable,
                SongArtistTable,
                SongMusicBrainzTable,
                ArtistSplitAliasTable,
                GenreTable,
                ArtistGenreTable,
                SongGenreTable,
                AlbumGenreTable,
                AlbumProviderTable,
                ProviderEnrichmentCheckTable,
                *allMusicBrainzTables
            )
        }

        every { storageService.albumsPath } returns null
        every { redisSearchService.isEnabled() } returns true

        service = AlbumService()
    }

    @AfterEach
    fun tearDown() {
        runBlocking { service.stopService() }
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertAlbum(name: String, barcode: String? = null): UUID {
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[AlbumTable.name] = name
                it[AlbumTable.barcode] = barcode
            }
        }
        return albumId
    }

    private fun insertSong(albumId: UUID, path: String): UUID {
        val songId = UUID.randomUUID()
        transaction(database) {
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[SongTable.albumId] = albumId
                it[filePath] = path
            }
        }
        return songId
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteAlbums returns true when every requested album is deleted`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val first = insertAlbum("First")
        val second = insertAlbum("Second")
        insertSong(first, "/missing/first/a.flac")
        insertSong(first, "/missing/first/b.flac")
        insertSong(first, "/missing/first/c.flac")
        insertSong(second, "/missing/second/a.flac")

        assertTrue(service.deleteAlbums(listOf(first, second, first)))
        assertNull(service.byId(first))
        assertNull(service.byId(second))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteAlbums returns false when a requested album does not exist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = insertAlbum("Existing")
        insertSong(albumId, "/missing/existing/a.flac")

        assertFalse(service.deleteAlbums(listOf(albumId, UUID.randomUUID())))
        assertNull(service.byId(albumId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteAlbums deletes song and variant files after commit and invalidates storage`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val albumDir = File(tempDir, "album").apply { mkdirs() }
            val songFile = File(albumDir, "track.flac").apply { writeText("audio") }
            val variantFile = File(albumDir, "track.atmos.m4a").apply { writeText("atmos") }

            val albumId = insertAlbum("With Variant")
            val songId = insertSong(albumId, songFile.absolutePath)
            transaction(database) {
                SongVariantTable.insert {
                    it[SongVariantTable.songId] = songId
                    it[kind] = SongVariantKind.ATMOS
                    it[path] = variantFile.absolutePath
                }
            }

            assertTrue(service.deleteAlbums(listOf(albumId)))

            assertFalse(songFile.exists())
            assertFalse(variantFile.exists())
            assertFalse(albumDir.exists())
            verify { storageService.invalidate(StorageCategory.TOTAL) }
            verify { redisSearchService.remove(SearchIndexEntityType.SONG, listOf(songId)) }
            verify { redisSearchService.remove(SearchIndexEntityType.ALBUM, match { albumId in it }) }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteAlbums keeps rows and files when redis removal fails`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        every { redisSearchService.remove(any(), any()) } throws IllegalStateException("redis down")
        val albumId = insertAlbum("Redis Down")
        insertSong(albumId, "/missing/redis/a.flac")

        assertTrue(service.deleteAlbums(listOf(albumId)))
        assertNull(service.byId(albumId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteEmptyAlbums removes the albums from the search index`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val emptyAlbum = insertAlbum("Empty")

        assertEquals(1, service.deleteEmptyAlbums())
        verify { redisSearchService.remove(SearchIndexEntityType.ALBUM, listOf(emptyAlbum)) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `barcode enrichment ids do not skip rows while checks are recorded`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val provider = "tidal"
        val albumIds = (1..2500).map { UUID.randomUUID() }
        transaction(database) {
            AlbumTable.batchInsert(albumIds) { albumId ->
                this[AlbumTable.id] = albumId
                this[AlbumTable.name] = "Album $albumId"
                this[AlbumTable.barcode] = "0000${albumId.toString().take(8)}"
            }
        }

        val seen = mutableListOf<UUID>()
        service.albumIdsForBarcodeEnrichment(provider).collect { id ->
            seen.add(id)
            service.updateProviderEnrichmentCheck(id, provider, ProviderEnrichmentType.ALBUM)
        }

        assertEquals(albumIds.toSet(), seen.toSet())
        assertEquals(albumIds.size, seen.size)
        val checked = transaction(database) {
            ProviderEnrichmentCheckTable.selectAll()
                .where { ProviderEnrichmentCheckTable.provider eq provider }
                .count()
        }
        assertEquals(albumIds.size.toLong(), checked)
    }
}
