package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.PluginManager
import dev.dertyp.testing.entityChangeTables
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class LibraryMergeServiceSingleFlightTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: LibraryMergeService
    private lateinit var albumService: AlbumService

    fun setup(dialect: DbDialect) {
        albumService = mockk()
        coEvery { albumService.rebuildVersionGroups() } returns 0
        val pluginManager = mockk<PluginManager>()
        every { pluginManager.getAllImporters() } returns emptyList()

        startKoin {
            modules(module {
                single { EntityChangeRecorder() }
                single { albumService }
                single { pluginManager }
                single { LibraryFileDeleter() }
                single { mockk<RedisSearchService>(relaxed = true) }
            })
        }

        database = TestDatabase.connect(dialect, "merge_single_flight_test")
        transaction(database) {
            SchemaUtils.create(
                *entityChangeTables,
                ArtistTable,
                AlbumTable,
                SongTable,
                SongVariantTable,
                ImageTable,
                PlaylistTable,
                UserTable,
                UserPlaylistTable,
                UserPlaylistSongTable,
                PlaylistSongTable,
                SongArtistTable,
                AlbumArtistTable,
                AlbumMusicBrainzTable,
                SongMusicBrainzTable,
                TranscodedSongTable,
                UserSongTable,
                SongProviderTable,
                AlbumProviderTable,
                CollectionTable,
                CollectionSongTable,
                CollectionAlbumTable,
                CollectionArtistTable,
                CollectionPlaylistTable,
                *allMusicBrainzTables
            )
        }
        service = LibraryMergeService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertDuplicatePair(label: String): List<UUID> {
        val groupId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        MBReleaseGroupTable.insert {
            it[MBReleaseGroupTable.id] = EntityID(groupId, MBReleaseGroupTable)
            it[MBReleaseGroupTable.title] = label
        }
        MBReleaseTable.insert {
            it[MBReleaseTable.id] = EntityID(releaseId, MBReleaseTable)
            it[MBReleaseTable.title] = label
            it[MBReleaseTable.releaseGroupId] = EntityID(groupId, MBReleaseGroupTable)
        }
        return listOf("$label first", "$label second").map { name ->
            val albumId = AlbumTable.insertAndGetId { it[AlbumTable.name] = name }
            AlbumMusicBrainzTable.insert {
                it[AlbumMusicBrainzTable.albumId] = albumId
                it[AlbumMusicBrainzTable.musicBrainzId] = EntityID(releaseId, MBReleaseTable)
            }
            albumId.value
        }
    }

    private fun existingAlbums(ids: List<UUID>) = transaction(database) {
        AlbumTable.selectAll().where { AlbumTable.id inList ids }.count()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `requests during a running album merge are coalesced into one follow-up run and never overlap`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)

            val firstPair = transaction(database) { insertDuplicatePair("First") }
            val secondPair = mutableListOf<UUID>()
            val thirdPair = mutableListOf<UUID>()

            val passes = AtomicInteger(0)
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)
            val firstPassEntered = CompletableDeferred<Unit>()
            val releaseFirstPass = CompletableDeferred<Unit>()

            coEvery { albumService.fetchMusicBrainzId(any(), any(), any(), any()) } coAnswers {
                val running = active.incrementAndGet()
                maxActive.accumulateAndGet(running) { a, b -> maxOf(a, b) }
                try {
                    when (passes.incrementAndGet()) {
                        1 -> {
                            secondPair += insertDuplicatePair("Second")
                            firstPassEntered.complete(Unit)
                            releaseFirstPass.await()
                        }

                        2 -> thirdPair += insertDuplicatePair("Third")
                    }
                } finally {
                    active.decrementAndGet()
                }
                null
            }

            val running = async(Dispatchers.Default) { service.mergeDuplicateAlbums() }
            withTimeout(30.seconds) { firstPassEntered.await() }

            val concurrent = withTimeout(30.seconds) {
                (1..3).map { async(Dispatchers.Default) { service.mergeDuplicateAlbums() } }.awaitAll()
            }
            assertEquals(listOf(0, 0, 0), concurrent)
            assertFalse(running.isCompleted)
            assertEquals(1, passes.get())

            releaseFirstPass.complete(Unit)
            val merged = withTimeout(30.seconds) { running.await() }

            assertEquals(2, merged)
            assertEquals(2, passes.get())
            assertEquals(1, maxActive.get())
            assertEquals(1L, existingAlbums(firstPair))
            assertEquals(1L, existingAlbums(secondPair))
            assertEquals(2L, existingAlbums(thirdPair))

            assertEquals(1, service.mergeDuplicateAlbums())
            assertEquals(3, passes.get())
            assertEquals(1L, existingAlbums(thirdPair))
        }
}
