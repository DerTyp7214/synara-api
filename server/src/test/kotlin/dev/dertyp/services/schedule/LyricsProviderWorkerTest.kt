package dev.dertyp.services.schedule

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.ServerConfig
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.Song
import dev.dertyp.db.*
import dev.dertyp.services.EntityEventPublisher
import dev.dertyp.services.SongService
import dev.dertyp.services.lyrics.LyricsCandidateService
import dev.dertyp.services.lyrics.providers.*
import dev.dertyp.services.models.LyricsMatch
import dev.dertyp.services.models.LyricsSyncLevel
import dev.dertyp.testing.insertAlbum
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.days

class LyricsProviderWorkerTest : KoinTest {
    private lateinit var database: Database
    private val songService = mockk<SongService>()

    private class RecordingProvider(override val id: String) : LyricsProvider {
        override val name = id
        override val url: String? = null
        override val usuallyWordSynced = true
        val asked = CopyOnWriteArrayList<Pair<String, HttpClientPriority>>()

        override suspend fun fetch(req: LyricsRequest, priority: HttpClientPriority): ProviderResult {
            asked += req.title to priority
            return ProviderResult.Miss
        }
    }

    private fun setup(dialect: DbDialect, provider: LyricsProvider) {
        startKoin {
            modules(module {
                single { mockk<EntityEventPublisher>(relaxed = true) }
                single { songService }
                single { ServerConfig(MapApplicationConfig("lyrics.providers" to provider.id)) }
                single<LyricsProvider>(named(provider.id)) { provider }
                single { LyricsProviderRegistry() }
                single { SidecarLyricsProvider() }
                single { LyricsCandidateService() }
            })
        }
        database = TestDatabase.connect(
            dialect, "lyrics_provider_worker_test",
            ImageTable, AnimatedImageTable, AlbumTable, SongTable, SyncedLyricsTable, LyricsCandidateTable,
        )
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun song(title: String, candidate: Triple<LyricsCandidateStatus, LyricsSyncLevel, Long>? = null): UUID {
        val songId = UUID.randomUUID()
        transaction(database) {
            val albumId = insertAlbum()
            SongTable.insert {
                it[id] = songId
                it[SongTable.title] = title
                it[SongTable.albumId] = albumId
                it[duration] = 200_000
            }
            candidate?.let { (status, level, age) ->
                LyricsCandidateTable.insert {
                    it[LyricsCandidateTable.songId] = songId
                    it[provider] = "online"
                    it[LyricsCandidateTable.status] = status
                    it[syncLevel] = level
                    it[match] = LyricsMatch.VERIFIED
                    it[fetchedAt] = System.currentTimeMillis() - age
                }
            }
        }
        coEvery { songService.byId(songId) } returns Song(
            id = songId,
            title = title,
            artists = listOf(ArtistCredit(UUID.randomUUID(), "The Band", isGroup = true)),
            album = null,
            duration = 200_000,
            explicit = false,
            path = "/nonexistent/$songId.flac",
        )
        return songId
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `worker asks providers at low priority for new, missed and line synced songs that are due`(dialect: DbDialect) = runBlocking {
        val provider = RecordingProvider("online")
        setup(dialect, provider)
        val old = 15.days.inWholeMilliseconds
        val recent = 1.days.inWholeMilliseconds
        song("Never asked")
        song("Old miss", Triple(LyricsCandidateStatus.MISS, LyricsSyncLevel.PLAIN, old))
        song("Old line", Triple(LyricsCandidateStatus.FOUND, LyricsSyncLevel.LINE, old))
        song("Recent miss", Triple(LyricsCandidateStatus.MISS, LyricsSyncLevel.PLAIN, recent))
        song("Recent line", Triple(LyricsCandidateStatus.FOUND, LyricsSyncLevel.LINE, recent))
        song("Old word", Triple(LyricsCandidateStatus.FOUND, LyricsSyncLevel.WORD, old))

        val results = LyricsProviderWorker().run()

        assertEquals(setOf("Never asked", "Old miss", "Old line"), provider.asked.map { it.first }.toSet())
        assertEquals(3, provider.asked.size)
        assertTrue(provider.asked.all { it.second == HttpClientPriority.LOW })
        assertEquals(1, results["withLyrics"])
        assertEquals(2, results["withoutLyrics"])
        assertEquals(0, results["failed"])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a second run right after the first asks nothing`(dialect: DbDialect) = runBlocking {
        val provider = RecordingProvider("online")
        setup(dialect, provider)
        song("Never asked")

        LyricsProviderWorker().run()
        val second = LyricsProviderWorker().run()

        assertEquals(1, provider.asked.size)
        assertEquals(emptyMap<String, Any?>(), second)
    }
}
