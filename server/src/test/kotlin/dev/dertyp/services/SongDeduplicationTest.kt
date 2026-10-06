package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.AudioInfo
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableSong
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.db.*
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.testing.entityChangeTables
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

@Suppress("UNCHECKED_CAST")
class SongDeduplicationTest : KoinTest {
    private lateinit var database: Database
    private val artistService = mockk<ArtistService>(relaxed = true)
    private val albumService = mockk<AlbumService>(relaxed = true)
    private val imageService = mockk<ImageService>(relaxed = true)
    private val genreService = mockk<GenreService>(relaxed = true)
    private val pluginManager = mockk<PluginManager>(relaxed = true)

    private val allTables = arrayOf(
        *entityChangeTables,
        ArtistTable, AlbumTable, SongTable, SongVariantTable, SongTitleTagTable, AlbumTitleTagTable, SongArtistTable,
        SongMusicBrainzTable, SongAudioDataTable, ImageTable, GenreTable,
        UserTable, AlbumMusicBrainzTable, ArtistMusicBrainzTable,
        ArtistAliasTable, ArtistMemberTable, AlbumArtistTable,
        PlaylistTable, UserSongTable, UserPlaylistTable,
        SongGenreTable, ArtistGenreTable, AlbumGenreTable,
        PlaylistSongTable, UserPlaylistSongTable,
        SyncedLyricsTable, ImageMetadataTable, RecentReleaseTable,
        FollowedArtistTable, TranscodedSongTable, CustomMigrationTable,
        ScheduledTaskLogTable, ArtistSplitAliasTable, SyncServiceTable,
        SongProviderTable,
        CollectionTable, CollectionSongTable, CollectionAlbumTable, CollectionArtistTable, CollectionPlaylistTable,
        *allMusicBrainzTables
    )

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "song_dedupe")
        transaction(database) {
            SchemaUtils.create(*allTables)
        }

        startKoin {
            modules(module {
                single { EntityChangeRecorder() }
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<CachedMusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { artistService }
                single { albumService }
                single { genreService }
                single { imageService }
                single { pluginManager }
                single { LibraryMergeService() }
                single { LibraryFileDeleter() }
                single { mockk<RedisSearchService>(relaxed = true) }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should not insert duplicate if originalUrl matches but path differs`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val songService = SongService()

            val artistId = UUID.randomUUID()
            val albumId = UUID.randomUUID()

            transaction(database) {
                ArtistTable.insert {
                    it[id] = artistId
                    it[name] = "Test Artist"
                }
                AlbumTable.insert {
                    it[id] = albumId
                    it[name] = "Test Album"
                }
            }

            coEvery { artistService.getOrBulkCreate(any()) } answers {
                val names = it.invocation.args[0] as List<String>
                names.associateWith { listOf(artistId) }
            }
            coEvery { albumService.getOrBulkCreate(any()) } answers {
                val albums = it.invocation.args[0] as List<InsertableAlbum>
                albums.associateWith { albumId }
            }

            val song1 = InsertableSong(
                title = "Dedupe Test",
                artists = listOf("Test Artist"),
                album = InsertableAlbum(name = "Test Album", artists = listOf("Test Artist")),
                path = "/old/path/song.flac",
                originalUrl = "https://tidal.com/track/dedupe-123",
                duration = 3.minutes.inWholeMilliseconds,
                explicit = false,
                audio = AudioInfo("flac", 44100, 16, 1000, 1000, 2)
            )

            val song2 = song1.copy(path = "/new/path/song.flac")

            songService.createBatch(listOf(song1))
            transaction(database) {
                assertEquals(1L, SongTable.selectAll().count())
            }

            songService.createBatch(listOf(song2))
            transaction(database) {
                assertEquals(
                    1L,
                    SongTable.selectAll().count(),
                    "Should not have inserted a second song when originalUrl matches"
                )
            }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should not insert duplicate if title and album match but path differs`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val songService = SongService()

            val artistId = UUID.randomUUID()
            val albumId = UUID.randomUUID()

            transaction(database) {
                ArtistTable.insert {
                    it[id] = artistId
                    it[name] = "Test Artist"
                }
                AlbumTable.insert {
                    it[id] = albumId
                    it[name] = "Test Album"
                }
            }

            coEvery { artistService.getOrBulkCreate(any()) } answers {
                val names = it.invocation.args[0] as List<String>
                names.associateWith { listOf(artistId) }
            }
            coEvery { albumService.getOrBulkCreate(any()) } answers {
                val albums = it.invocation.args[0] as List<InsertableAlbum>
                albums.associateWith { albumId }
            }

            val song1 = InsertableSong(
                title = "Dedupe Test Metadata",
                artists = listOf("Test Artist"),
                album = InsertableAlbum(name = "Test Album", artists = listOf("Test Artist")),
                path = "/old/path/song2.flac",
                originalUrl = "",
                trackNumber = 1,
                discNumber = 1,
                duration = 3.minutes.inWholeMilliseconds,
                explicit = false,
                audio = AudioInfo("flac", 44100, 16, 1000, 1000, 2)
            )

            val song2 = song1.copy(path = "/new/path/song2.flac")

            songService.createBatch(listOf(song1))
            transaction(database) {
                assertEquals(1L, SongTable.selectAll().count())
            }

            songService.createBatch(listOf(song2))
            transaction(database) {
                assertEquals(
                    1L,
                    SongTable.selectAll().count(),
                    "Should not have inserted a second song when metadata matches"
                )
            }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should not insert duplicate if ISRC matches`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()

        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val isrc = "USAT20300184"

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
        }

        coEvery { artistService.getOrBulkCreate(any()) } answers {
            val names = it.invocation.args[0] as List<String>
            names.associateWith { listOf(artistId) }
        }
        coEvery { albumService.getOrBulkCreate(any()) } answers {
            val albums = it.invocation.args[0] as List<InsertableAlbum>
            albums.associateWith { albumId }
        }

        val song1 = InsertableSong(
            title = "Title A",
            artists = listOf("Test Artist"),
            album = InsertableAlbum(name = "Test Album", artists = listOf("Test Artist")),
            path = "/path/1.flac",
            originalUrl = "https://service1.com/track/1",
            isrc = isrc,
            duration = 180000,
            explicit = false
        )

        val song2 = song1.copy(
            title = "Title B",
            path = "/path/2.flac",
            originalUrl = "https://service2.com/track/2"
        )

        songService.createBatch(listOf(song1))
        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count())
        }

        songService.createBatch(listOf(song2))
        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count(), "Should not have inserted a second song when ISRC matches")
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should insert a duplicate when ISRC matches but album differs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()

        val artistId = UUID.randomUUID()
        val isrc = "USAT20300184"

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
        }

        // Resolve each album name to a real, inserted album row (mirrors getOrBulkCreate creating albums).
        val albumIdsByName = mutableMapOf<String, UUID>()
        coEvery { artistService.getOrBulkCreate(any()) } answers {
            val names = it.invocation.args[0] as List<String>
            names.associateWith { listOf(artistId) }
        }
        coEvery { albumService.getOrBulkCreate(any()) } answers {
            val albums = it.invocation.args[0] as List<InsertableAlbum>
            albums.associateWith { album ->
                albumIdsByName.getOrPut(album.name) {
                    val newId = UUID.randomUUID()
                    transaction(database) { AlbumTable.insert { it[id] = newId; it[name] = album.name } }
                    newId
                }
            }
        }

        val single = InsertableSong(
            title = "Title A",
            artists = listOf("Test Artist"),
            album = InsertableAlbum(name = "The Single", artists = listOf("Test Artist")),
            path = "/path/single.flac",
            originalUrl = "https://tidal.com/track/single-id",
            isrc = isrc,
            duration = 180000,
            explicit = false
        )

        val albumVersion = single.copy(
            album = InsertableAlbum(name = "The Album", artists = listOf("Test Artist")),
            path = "/path/album.flac",
            originalUrl = "https://tidal.com/track/album-id"
        )

        songService.createBatch(listOf(single))
        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count())
        }

        songService.createBatch(listOf(albumVersion))
        transaction(database) {
            assertEquals(
                2L,
                SongTable.selectAll().count(),
                "Same ISRC on a different album must be inserted as a separate song"
            )
        }
    }

    private data class StoredAlbum(
        val albumName: String,
        val editionTags: List<TitleTag> = emptyList(),
        val code: String? = null,
        val releaseId: UUID? = null
    )

    private suspend fun songsAfterImporting(
        dialect: DbDialect,
        first: Pair<InsertableAlbum, StoredAlbum>,
        second: Pair<InsertableAlbum, StoredAlbum>
    ): Long {
        setup(dialect)
        val songService = SongService()
        val artistId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
        }

        val albumRows = mutableMapOf<StoredAlbum, UUID>()
        val storedByAlbum = listOf(first, second)
        coEvery { artistService.getOrBulkCreate(any()) } answers {
            val names = it.invocation.args[0] as List<String>
            names.associateWith { listOf(artistId) }
        }
        coEvery { albumService.getOrBulkCreate(any()) } answers {
            val albums = it.invocation.args[0] as List<InsertableAlbum>
            albums.associateWith { album ->
                val stored = storedByAlbum.first { (incoming, _) -> incoming === album }.second
                albumRows.getOrPut(stored) {
                    val newId = UUID.randomUUID()
                    transaction(database) {
                        AlbumTable.insert {
                            it[id] = newId
                            it[name] = stored.albumName
                            it[titleTags] = encodeTitleTags(stored.editionTags)
                            it[barcode] = stored.code
                        }
                        stored.releaseId?.let { release ->
                            MBReleaseTable.insert {
                                it[id] = EntityID(release, MBReleaseTable)
                                it[title] = stored.albumName
                            }
                            AlbumMusicBrainzTable.insert {
                                it[albumId] = newId
                                it[musicBrainzId] = EntityID(release, MBReleaseTable)
                            }
                        }
                    }
                    newId
                }
            }
        }

        val existing = InsertableSong(
            title = "Stay",
            artists = listOf("Test Artist"),
            album = first.first,
            path = "/path/first.flac",
            originalUrl = "https://tidal.com/track/first-id",
            isrc = "USAT20300184",
            duration = 180000,
            explicit = false
        )
        val incoming = existing.copy(
            album = second.first,
            path = "/path/second.flac",
            originalUrl = "https://tidal.com/track/second-id"
        )

        songService.createBatch(listOf(existing))
        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count())
        }

        songService.createBatch(listOf(incoming))
        return transaction(database) { SongTable.selectAll().count() }
    }

    private fun album(
        albumName: String,
        code: String? = null,
        providerId: String? = null,
        releaseId: UUID? = null
    ) = InsertableAlbum(
        name = albumName,
        artists = listOf("Test Artist"),
        originalId = providerId,
        barcode = code,
        musicBrainzId = releaseId
    )

    private val anniversary = listOf(TitleTag(TitleTagKind.VERSION, "10th Anniversary"))

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch inserts a song with the same ISRC when the album barcodes differ`(dialect: DbDialect) =
        runBlocking {
            val songs = songsAfterImporting(
                dialect,
                album("The Album", code = "0602547933522", providerId = "tidal:1") to
                        StoredAlbum("The Album", code = "0602547933522"),
                album("The Album", code = "0093624814337", providerId = "tidal:2") to
                        StoredAlbum("The Album", code = "0093624814337")
            )

            assertEquals(2L, songs, "Different barcodes are different editions even with equal name and tags")
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch dedupes by ISRC when the album barcodes are equal`(dialect: DbDialect) = runBlocking {
        val stored = StoredAlbum("The Album", code = "602547933522")
        val songs = songsAfterImporting(
            dialect,
            album("The Album", code = "602547933522", providerId = "tidal:1") to stored,
            album("The Album", code = "00602547933522", providerId = "tidal:2") to stored
        )

        assertEquals(1L, songs, "Equal barcodes dedupe despite zero padding and a changed provider album id")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch lets equal barcodes decide over a different album name`(dialect: DbDialect) = runBlocking {
        val stored = StoredAlbum("The Album", code = "0602547933522")
        val songs = songsAfterImporting(
            dialect,
            album("The Album", code = "0602547933522") to stored,
            album("The Album (10th Anniversary)", code = "602547933522") to stored
        )

        assertEquals(1L, songs, "Name and tags are not consulted when both albums have a barcode")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch dedupes by ISRC on equal album name and tags when one side has no barcode`(dialect: DbDialect) =
        runBlocking {
            val stored = StoredAlbum("The Album", editionTags = anniversary, code = "0602547933522")
            val songs = songsAfterImporting(
                dialect,
                album("The Album (10th Anniversary)", code = "0602547933522", providerId = "tidal:1") to stored,
                album("The Album (10TH ANNIVERSARY)", providerId = "tidal:2") to stored
            )

            assertEquals(1L, songs, "Equal name and tags are the same edition without a barcode on one side")
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch inserts a song with the same ISRC when the album tags differ`(dialect: DbDialect) =
        runBlocking {
            val songs = songsAfterImporting(
                dialect,
                album("The Album") to StoredAlbum("The Album"),
                album("The Album (10th Anniversary)") to StoredAlbum("The Album", editionTags = anniversary)
            )

            assertEquals(2L, songs, "The anniversary edition must keep its own copy of the song")
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch inserts a song with the same ISRC when the albums carry different releases`(dialect: DbDialect) =
        runBlocking {
            val firstRelease = UUID.randomUUID()
            val secondRelease = UUID.randomUUID()
            val songs = songsAfterImporting(
                dialect,
                album("The Album", releaseId = firstRelease) to StoredAlbum("The Album", releaseId = firstRelease),
                album("The Album", releaseId = secondRelease) to StoredAlbum("The Album", releaseId = secondRelease)
            )

            assertEquals(2L, songs, "Two different MusicBrainz releases are different editions")
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should not insert duplicate if different URLs point to the same song via SongProviderTable`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val songService = SongService()

            val artistId = UUID.randomUUID()
            val albumId = UUID.randomUUID()

            transaction(database) {
                ArtistTable.insert {
                    it[id] = artistId
                    it[name] = "Test Artist"
                }
                AlbumTable.insert {
                    it[id] = albumId
                    it[name] = "Test Album"
                }
            }

            coEvery { artistService.getOrBulkCreate(any()) } answers {
                val names = it.invocation.args[0] as List<String>
                names.associateWith { listOf(artistId) }
            }
            coEvery { albumService.getOrBulkCreate(any()) } answers {
                val albums = it.invocation.args[0] as List<InsertableAlbum>
                albums.associateWith { albumId }
            }

            val song1 = InsertableSong(
                title = "Dedupe Test Provider",
                artists = listOf("Test Artist"),
                album = InsertableAlbum(name = "Test Album", artists = listOf("Test Artist")),
                path = "/old/path/song3.flac",
                originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                duration = 3.minutes.inWholeMilliseconds,
                explicit = false,
                audio = AudioInfo("flac", 44100, 16, 1000, 1000, 2)
            )

            val song2 = song1.copy(
                path = "/new/path/song3.flac",
                originalUrl = "https://youtu.be/dQw4w9WgXcQ"
            )

            songService.createBatch(listOf(song1))
            transaction(database) {
                assertEquals(1L, SongTable.selectAll().count())
                assertEquals(1L, SongProviderTable.selectAll().count())
            }

            songService.createBatch(listOf(song2))
            transaction(database) {
                assertEquals(
                    1L,
                    SongTable.selectAll().count(),
                    "Should not have inserted a second song when provider ID matches"
                )
            }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mergeDuplicateSongs should merge SongProviderTable entries`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val libraryMergeService = get<LibraryMergeService>()

        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId1 = UUID.randomUUID()
        val songId2 = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }

            SongTable.insert {
                it[id] = songId1
                it[title] = "Duplicate"
                it[SongTable.albumId] = albumId
                it[filePath] = "/path/1"
                it[duration] = 1000
                it[fileSize] = 1000
                it[inserted] = 100
            }
            SongProviderTable.insert {
                it[songId] = songId1
                it[provider] = "provider1"
                it[externalId] = "id1"
                it[rawUrl] = "url1"
            }

            SongTable.insert {
                it[id] = songId2
                it[title] = "Duplicate"
                it[SongTable.albumId] = albumId
                it[filePath] = "/path/1"
                it[duration] = 1000
                it[fileSize] = 1000
                it[inserted] = 200
            }
            SongProviderTable.insert {
                it[songId] = songId2
                it[provider] = "provider2"
                it[externalId] = "id2"
                it[rawUrl] = "url2"
            }
        }

        val merged = transaction(database) { libraryMergeService.mergeDuplicateSongs() }
        assertEquals(1, merged)

        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count())
            val providers = SongProviderTable.selectAll().where { SongProviderTable.songId eq songId1 }.toList()
            assertEquals(2, providers.size)
            assertTrue(providers.any { it[SongProviderTable.provider] == "provider1" })
            assertTrue(providers.any { it[SongProviderTable.provider] == "provider2" })
        }
    }
}
