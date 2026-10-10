package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.db.*
import dev.dertyp.services.credentials.CredentialProvider
import dev.dertyp.services.metadata.*
import dev.dertyp.testing.FakeCredentialProvider
import dev.dertyp.testing.RecordedEntityEvents
import dev.dertyp.testing.entityChangeTables
import dev.dertyp.testing.entityEventsModule
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import java.io.File
import java.util.UUID

class SongServiceDeletionTest : KoinTest {
    private lateinit var database: Database
    private lateinit var songService: SongService

    private val musicBrainzService = mockk<MusicBrainzService>(relaxed = true)
    private val environment = mockk<ApplicationEnvironment>()
    private val storageService = mockk<StorageService>(relaxed = true)
    private val fingerprintService = mockk<AcoustIdFingerprintService>()
    private val redisSearchService = mockk<RedisSearchService>(relaxed = true)
    private val events = RecordedEntityEvents().subscribeLibraryReactions()

    @TempDir
    lateinit var tempDir: File

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                includes(entityEventsModule(events))
                single { environment }
                single { musicBrainzService }
                single { MusicBrainzCacheService() }
                single { CachedMusicBrainzService(get(), get()) }
                single { AcoustIdService(fingerprintService) }
                single<CredentialProvider> {
                    FakeCredentialProvider(
                        ResolvedCredential.ApiKey(
                            CredentialNames.ACOUSTID_API,
                            "testKey"
                        )
                    )
                }
                single { mockk<ImageService>(relaxed = true) }
                single { storageService }
                single { mockk<MetadataFetchingService>(relaxed = true) }
                single { AlbumService() }
                single { ArtistService() }
                single { GenreService() }
                single { LibraryMergeService() }
                single { redisSearchService }
                single { LibraryFileDeleter() }
            })
        }

        database = TestDatabase.connect(
            dialect, "song_deletion_test",
            *entityChangeTables,
            UserTable,
            SongTable, SongVariantTable,
            AlbumTable,
            ArtistTable,
            ArtistMemberTable,
            SongArtistTable,
            AlbumArtistTable,
            SongMusicBrainzTable,
            SongAcoustIdTable,
            AlbumMusicBrainzTable,
            ArtistMusicBrainzTable,
            UserSongTable,
            UserCapabilityTable,
            ArtistAliasTable,
            FollowedArtistTable,
            PlaylistSongTable,
            UserPlaylistSongTable,
            UserPlaylistShareTable,
            ImageTable,
            ImageMetadataTable,
            AnimatedImageTable,
            ArtistSplitAliasTable,
            GenreTable,
            ArtistGenreTable,
            SongGenreTable,
            AlbumGenreTable,
            SongProviderTable,
            AlbumProviderTable,
            SongAudioDataTable,
            TimecodeTagTable,
            ProviderEnrichmentCheckTable,
            *allMusicBrainzTables
        )

        coEvery { fingerprintService.fingerprint(any()) } returns null
        every { storageService.albumsPath } returns null
        every { redisSearchService.isEnabled() } returns true

        songService = SongService()
    }

    @AfterEach
    fun tearDown() {
        runBlocking { events.stop() }
        if (::songService.isInitialized) runBlocking { songService.stopService() }
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertAlbum(): UUID {
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
        }
        return albumId
    }

    private fun insertSong(albumId: UUID, path: String, isrcValue: String? = null): UUID {
        val songId = UUID.randomUUID()
        transaction(database) {
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[SongTable.albumId] = albumId
                it[filePath] = path
                it[isrc] = isrcValue
            }
        }
        return songId
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteSongs deletes files and variants after commit and removes search entries`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val albumDir = File(tempDir, "album").apply { mkdirs() }
            val songFile = File(albumDir, "track.flac").apply { writeText("audio") }
            val variantFile = File(albumDir, "track.atmos.m4a").apply { writeText("atmos") }

            val albumId = insertAlbum()
            val songId = insertSong(albumId, songFile.absolutePath)
            transaction(database) {
                SongVariantTable.insert {
                    it[SongVariantTable.songId] = songId
                    it[kind] = SongVariantKind.ATMOS
                    it[path] = variantFile.absolutePath
                }
            }

            assertTrue(songService.deleteSongs(listOf(songId)))

            assertFalse(songFile.exists())
            assertFalse(variantFile.exists())
            assertFalse(albumDir.exists())
            transaction(database) {
                assertEquals(0, SongTable.selectAll().where { SongTable.id eq songId }.count())
                assertEquals(0, AlbumTable.selectAll().where { AlbumTable.id eq albumId }.count())
            }
            verify { storageService.invalidate(StorageCategory.TOTAL) }
            verify(timeout = 5000) { redisSearchService.remove(SearchIndexEntityType.SONG, listOf(songId)) }
            verify(timeout = 5000) { redisSearchService.remove(SearchIndexEntityType.ALBUM, match { albumId in it }) }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteSongs rebuilds the album version groups when it removes an album of a group`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val spied = spyk(get<AlbumService>())
            coEvery { spied.rebuildVersionGroups() } returns 0
            loadKoinModules(module { single<AlbumService> { spied } })

            val removedAlbum = insertAlbum()
            val memberAlbum = insertAlbum()
            val removedSong = insertSong(removedAlbum, "/missing/removed.flac")
            val memberSong = insertSong(memberAlbum, "/missing/member-a.flac")
            insertSong(memberAlbum, "/missing/member-b.flac")
            val sharedGroup = transaction(database) {
                val created = AlbumVersionGroupTable.insertAndGetId { }
                AlbumTable.update({ AlbumTable.id inList listOf(removedAlbum, memberAlbum) }) {
                    it[versionGroupId] = created
                }
                created.value
            }

            assertTrue(songService.deleteSongs(listOf(memberSong)))
            coVerify(exactly = 0) { spied.rebuildVersionGroups() }

            assertTrue(songService.deleteSongs(listOf(removedSong)))

            coVerify(timeout = 5000, exactly = 1) { spied.rebuildVersionGroups() }
            transaction(database) {
                assertEquals(0, AlbumTable.selectAll().where { AlbumTable.id eq removedAlbum }.count())
                assertEquals(
                    sharedGroup,
                    AlbumTable.selectAll().where { AlbumTable.id eq memberAlbum }.single()[AlbumTable.versionGroupId]?.value
                )
            }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteSongs compares the deleted count with the requested ids`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = insertAlbum()
        val first = insertSong(albumId, "/missing/a.flac")
        val second = insertSong(albumId, "/missing/b.flac")

        assertFalse(songService.deleteSongs(listOf(first, UUID.randomUUID())))
        assertTrue(songService.deleteSongs(listOf(second)))
        verify(exactly = 2) { storageService.invalidate(StorageCategory.TOTAL) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteSongs succeeds when redis removal fails`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        every { redisSearchService.remove(any(), any()) } throws IllegalStateException("redis down")
        val albumId = insertAlbum()
        val songId = insertSong(albumId, "/missing/a.flac")

        assertTrue(songService.deleteSongs(listOf(songId)))
        transaction(database) {
            assertEquals(0, SongTable.selectAll().where { SongTable.id eq songId }.count())
        }
        verify(timeout = 5000) { redisSearchService.remove(SearchIndexEntityType.SONG, listOf(songId)) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `isrc enrichment ids do not skip rows while checks are recorded`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val provider = "tidal"
        val albumId = insertAlbum()
        val songIds = (1..2500).map { UUID.randomUUID() }
        transaction(database) {
            SongTable.batchInsert(songIds) { songId ->
                this[SongTable.id] = songId
                this[SongTable.title] = "Song $songId"
                this[SongTable.albumId] = albumId
                this[SongTable.filePath] = "/missing/$songId.flac"
                this[SongTable.isrc] = "ISRC${songId.toString().take(8)}"
            }
        }

        val seen = mutableListOf<UUID>()
        songService.songIdsForIsrcEnrichment(provider).collect { id ->
            seen.add(id)
            songService.updateProviderEnrichmentCheck(id, provider, ProviderEnrichmentType.SONG)
        }

        assertEquals(songIds.toSet(), seen.toSet())
        assertEquals(songIds.size, seen.size)
    }
}
