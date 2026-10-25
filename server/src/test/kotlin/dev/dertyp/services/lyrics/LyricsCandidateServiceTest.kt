package dev.dertyp.services.lyrics

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.ServerConfig
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.Song
import dev.dertyp.db.*
import dev.dertyp.serializers.AppCbor
import dev.dertyp.services.SongService
import dev.dertyp.services.lyrics.providers.*
import dev.dertyp.services.models.*
import dev.dertyp.data.EntityType
import dev.dertyp.services.EntityEventPublisher
import dev.dertyp.testing.insertAlbum
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalSerializationApi::class)
class LyricsCandidateServiceTest {
    private lateinit var database: Database
    private lateinit var service: LyricsCandidateService
    private val songService = mockk<SongService>()
    private val entityEvents = mockk<EntityEventPublisher>(relaxed = true)

    private class FakeProvider(
        override val id: String,
        var result: ProviderResult = ProviderResult.Miss,
    ) : LyricsProvider {
        override val name = "Fake $id"
        override val url: String? = null
        override val usuallyWordSynced = false
        var calls = 0

        override suspend fun fetch(req: LyricsRequest, priority: HttpClientPriority): ProviderResult {
            calls++
            return result
        }
    }

    private class SlowProvider(
        override val id: String,
        private val after: Duration,
        private val result: ProviderResult,
    ) : LyricsProvider {
        override val name = "Slow $id"
        override val url: String? = null
        override val usuallyWordSynced = false

        override suspend fun fetch(req: LyricsRequest, priority: HttpClientPriority): ProviderResult {
            delay(after)
            return result
        }
    }

    private fun setup(dialect: DbDialect, vararg providers: LyricsProvider) {
        startKoin {
            modules(module {
                single { entityEvents }
                single { songService }
                single { ServerConfig(MapApplicationConfig("lyrics.providers" to providers.joinToString(",") { it.id })) }
                providers.forEach { provider -> single<LyricsProvider>(named(provider.id)) { provider } }
                single { LyricsProviderRegistry() }
                single { SidecarLyricsProvider() }
            })
        }
        database = TestDatabase.connect(
            dialect, "lyrics_candidate_test",
            ImageTable, AnimatedImageTable, AlbumTable, SongTable, SyncedLyricsTable, LyricsCandidateTable,
        )
        service = LyricsCandidateService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun song(
        title: String = "Hello World",
        durationMs: Long = 200_000,
        lyrics: String = "",
        songId: UUID = UUID.randomUUID(),
        path: String = "/nonexistent/$songId.flac",
    ): UUID {
        transaction(database) {
            val albumId = insertAlbum()
            SongTable.insert {
                it[id] = songId
                it[SongTable.title] = title
                it[SongTable.albumId] = albumId
                it[duration] = durationMs
                it[SongTable.lyrics] = lyrics
            }
        }
        coEvery { songService.byId(songId) } returns Song(
            id = songId,
            title = title,
            artists = listOf(ArtistCredit(UUID.randomUUID(), "The Band", isGroup = true)),
            album = null,
            duration = durationMs,
            explicit = false,
            path = path,
        )
        return songId
    }

    private val lineLrc = "[00:01.00]Hello there\n[00:03.00]General words"
    private val wordLrc = "[00:01.00]<00:01.00>Hello <00:01.50>there\n[00:03.00]<00:03.00>General <00:03.40>words"

    private fun found(lrc: String, title: String? = null, artist: String? = null, durationSec: Int? = null) =
        ProviderResult.Found(LyricsPayload.Lrc(lrc), trackTitle = title, trackArtist = artist, durationSec = durationSec)

    private fun line(start: Long, text: String, words: List<LyricWord> = emptyList()) =
        LyricLine(start.milliseconds, (start + 1000).milliseconds, words, text)

    private fun word(start: Long, text: String) = LyricWord(text, start.milliseconds, (start + 400).milliseconds)

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `word synced beats line synced regardless of provider priority`(dialect: DbDialect) = runBlocking {
        val first = FakeProvider("first", found(lineLrc))
        val second = FakeProvider("second", found(wordLrc))
        setup(dialect, first, second)
        val songId = song()

        val result = service.findLyrics(songId)

        assertEquals(listOf("second", "first"), result.candidates.map { it.provider.id })
        assertEquals(listOf(LyricsSyncLevel.WORD, LyricsSyncLevel.LINE), result.candidates.map { it.syncLevel })
        assertEquals(listOf(1, 2), result.candidates.map { it.rank })
        assertEquals(result.candidates.first().id, result.defaultCandidateId)
        transaction(database) {
            val ranks = LyricsCandidateTable.selectAll().associate { it[LyricsCandidateTable.provider] to it[LyricsCandidateTable.rank] }
            assertEquals(mapOf("second" to 1, "first" to 2), ranks)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `verified beats provider match and a failed match is stored as a miss`(dialect: DbDialect) = runBlocking {
        val unverified = FakeProvider("unverified", found(lineLrc))
        val verified = FakeProvider("verified", found(lineLrc, "Hello World (Remastered)", "The Band", 201))
        val wrong = FakeProvider("wrong", found(wordLrc, "Hello World", "Somebody Else", 200))
        setup(dialect, unverified, verified, wrong)
        val songId = song()

        val result = service.findLyrics(songId)

        assertEquals(listOf("verified", "unverified"), result.candidates.map { it.provider.id })
        assertEquals(LyricsMatch.VERIFIED, result.candidates.first().match)
        assertEquals(1, result.candidates.first().durationGapSec)
        transaction(database) {
            val wrongRow = LyricsCandidateTable.selectAll().where { LyricsCandidateTable.provider eq "wrong" }.single()
            assertEquals(LyricsCandidateStatus.MISS, wrongRow[LyricsCandidateTable.status])
            assertNull(wrongRow[LyricsCandidateTable.content])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `manual ranks first then sidecar then the rest`(dialect: DbDialect) = runBlocking {
        setup(dialect, FakeProvider("online", found(wordLrc)))
        val directory = createTempDirectory("lyrics_sidecar").toFile().also { it.deleteOnExit() }
        val songId = UUID.randomUUID()
        File(directory, "$songId.lrc").apply { writeText(lineLrc); deleteOnExit() }
        song(lyrics = "plain words", songId = songId, path = File(directory, "$songId.flac").path)

        service.findLyrics(songId)
        val result = service.setSyncedLyrics(songId, SyncedLyrics(listOf(line(1000, "Manual line"))))

        assertEquals(
            listOf(LyricsCandidateService.MANUAL, SidecarLyricsProvider.ID, "online", LyricsCandidateService.SONG),
            result.candidates.map { it.provider.id }
        )
        assertEquals(LyricsMatch.VERIFIED, result.candidates.first().match)
        assertEquals(LyricsSyncLevel.LINE, result.candidates.first().syncLevel)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `whisperx ranks after online word candidates and before line candidates`(dialect: DbDialect) = runBlocking {
        val word = FakeProvider("word", found(wordLrc))
        val lineOnly = FakeProvider("line", found(lineLrc))
        setup(dialect, word, lineOnly)
        val songId = song()
        transaction(database) {
            SyncedLyricsTable.insert {
                it[SyncedLyricsTable.songId] = songId
                it[content] = AppCbor.encodeToByteArray(SyncedLyrics(listOf(line(0, "Hi", listOf(word(0, "Hi"))))))
            }
        }

        val result = service.findLyrics(songId)

        assertEquals(listOf("word", LyricsCandidateService.WHISPERX, "line"), result.candidates.map { it.provider.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a pin changes the default but not the ranks`(dialect: DbDialect) = runBlocking {
        setup(dialect, FakeProvider("first", found(wordLrc)), FakeProvider("second", found(lineLrc)))
        val songId = song()
        val before = service.findLyrics(songId)
        val second = before.candidates.single { it.provider.id == "second" }

        val pinned = service.setPreferredLyrics(songId, second.id)

        assertEquals(second.id, pinned.defaultCandidateId)
        assertEquals(before.candidates.map { it.id to it.rank }, pinned.candidates.map { it.id to it.rank })
        assertTrue(pinned.candidates.single { it.id == second.id }.preferred)
        assertEquals(second.id, service.getLyrics(songId)?.id)
        assertEquals(second.id, service.lyricsInfoFor(listOf(songId))[songId]?.defaultCandidateId)

        val unpinned = service.setPreferredLyrics(songId, null)
        assertEquals(before.defaultCandidateId, unpinned.defaultCandidateId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setSyncedLyrics validates the lines and null removes the manual candidate`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = song()

        assertThrows<IllegalArgumentException> {
            service.setSyncedLyrics(songId, SyncedLyrics(listOf(line(5000, "Second"), line(1000, "First"))))
        }
        assertThrows<IllegalArgumentException> {
            service.setSyncedLyrics(
                songId,
                SyncedLyrics(listOf(line(1000, "Hello there", listOf(word(1000, "Hello "), word(1400, "where")))))
            )
        }
        assertThrows<IllegalArgumentException> {
            service.setSyncedLyrics(
                songId,
                SyncedLyrics(listOf(line(1000, "Hello", listOf(word(500, "Hello")))))
            )
        }
        assertTrue(service.getLyricsCandidates(songId).candidates.isEmpty())

        val stored = service.setSyncedLyrics(
            songId,
            SyncedLyrics(listOf(line(1000, "Hello there", listOf(word(1000, "Hel"), word(1200, "lo "), word(1400, "there")))))
        )
        val manual = stored.candidates.single()
        assertEquals(LyricsSyncLevel.WORD, manual.syncLevel)
        assertEquals("Hello there", manual.plain)
        verify(exactly = 1) { entityEvents.updated(EntityType.SONG, listOf(songId)) }

        val removed = service.setSyncedLyrics(songId, null)
        assertTrue(removed.candidates.isEmpty())
        assertNull(removed.defaultCandidateId)
        transaction(database) { assertEquals(0L, LyricsCandidateTable.selectAll().count()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a refresh asks every provider again and never overwrites the manual candidate`(dialect: DbDialect) = runBlocking {
        val provider = FakeProvider("online", found(lineLrc))
        setup(dialect, provider)
        val songId = song()
        val manualLyrics = SyncedLyrics(listOf(line(1000, "Mine")))
        service.setSyncedLyrics(songId, manualLyrics)
        service.findLyrics(songId)
        provider.result = found(wordLrc)

        val result = service.findLyrics(songId, refresh = true)

        assertEquals(2, provider.calls)
        val manual = result.candidates.first()
        assertEquals(LyricsCandidateService.MANUAL, manual.provider.id)
        assertEquals(manualLyrics, service.getLyricsCandidate(manual.id)?.lyrics)
        assertEquals(LyricsSyncLevel.WORD, result.candidates.single { it.provider.id == "online" }.syncLevel)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `importSyncedLyrics of an enhanced LRC stores word timing`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val songId = song()

        val result = service.importSyncedLyrics(songId, LyricsImport(LyricsImportFormat.LRC, wordLrc))

        val manual = result.candidates.single()
        assertEquals(LyricsCandidateService.MANUAL, manual.provider.id)
        assertEquals(LyricsSyncLevel.WORD, manual.syncLevel)
        val lyrics = assertNotNull(service.getLyrics(songId)?.lyrics)
        assertEquals(listOf("Hello ", "there"), lyrics.lines.first().words.map { it.text })
        assertEquals(1500.milliseconds, lyrics.lines.first().words[1].startTime)
        assertThrows<IllegalArgumentException> { service.importSyncedLyrics(songId, LyricsImport(LyricsImportFormat.LRC, "")) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `importSyncedLyrics of a lyricsfile stores word timing`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val songId = song()

        val result = service.importSyncedLyrics(songId, LyricsImport(LyricsImportFormat.LYRICSFILE, lyricsFile))

        val manual = result.candidates.single()
        assertEquals(LyricsCandidateService.MANUAL, manual.provider.id)
        assertEquals(LyricsSyncLevel.WORD, manual.syncLevel)
        val lyrics = assertNotNull(service.getLyrics(songId)?.lyrics)
        assertEquals(listOf("Hello ", "there"), lyrics.lines.first().words.map { it.text })
        assertEquals(1500.milliseconds, lyrics.lines.first().words[1].startTime)
        assertThrows<IllegalArgumentException> {
            service.importSyncedLyrics(songId, LyricsImport(LyricsImportFormat.LYRICSFILE, "version: '1.0'\nlines: []\n"))
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `lyricsfile sidecar is a verified word candidate`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val directory = createTempDirectory("lyrics_sidecar").toFile().also { it.deleteOnExit() }
        val songId = UUID.randomUUID()
        File(directory, "$songId.lyricsfile.yaml").apply { writeText(lyricsFile); deleteOnExit() }
        song(songId = songId, path = File(directory, "$songId.flac").path)

        val result = service.findLyrics(songId)

        val sidecar = result.candidates.single()
        assertEquals(SidecarLyricsProvider.ID, sidecar.provider.id)
        assertEquals(LyricsSyncLevel.WORD, sidecar.syncLevel)
        assertEquals(LyricsMatch.VERIFIED, sidecar.match)
        assertEquals("Hello there\nSecond line", sidecar.plain)
    }

    private val lyricsFile = """
        version: '1.0'
        metadata:
          title: 'Hello World'
          artist: 'The Band'
          duration_ms: 200000
        lines:
          - text: 'Hello there'
            start_ms: 1000
            end_ms: 2500
            words:
              - text: 'Hello '
                start_ms: 1000
              - text: 'there'
                start_ms: 1500
                end_ms: 2500
          - text: 'Second line'
            start_ms: 3000
    """.trimIndent()

    private fun age(songId: UUID, provider: String, by: Long) = transaction(database) {
        LyricsCandidateTable.update({ LyricsCandidateTable.songId eq songId and (LyricsCandidateTable.provider eq provider) }) {
            it[fetchedAt] = System.currentTimeMillis() - by
        }
    }

    private fun fetchedAt(songId: UUID, provider: String): Long = transaction(database) {
        LyricsCandidateTable.selectAll()
            .where { LyricsCandidateTable.songId eq songId and (LyricsCandidateTable.provider eq provider) }
            .single()[LyricsCandidateTable.fetchedAt]
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `due line candidates are upgraded to word timing and fresh ones are not asked`(dialect: DbDialect) = runBlocking {
        val provider = FakeProvider("online", found(lineLrc))
        setup(dialect, provider)
        val songId = song()
        val first = service.findLyrics(songId).candidates.single()

        provider.result = found(wordLrc)
        service.findLyrics(songId)
        assertEquals(1, provider.calls)

        age(songId, "online", 15.days.inWholeMilliseconds)
        val upgraded = service.findLyrics(songId).candidates.single()
        assertEquals(2, provider.calls)
        assertEquals(first.id, upgraded.id)
        assertEquals(LyricsSyncLevel.WORD, upgraded.syncLevel)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a due line candidate without new word timing only gets a new fetch time`(dialect: DbDialect) = runBlocking {
        val provider = FakeProvider("online", found(lineLrc))
        setup(dialect, provider)
        val songId = song()
        service.findLyrics(songId)
        val old = System.currentTimeMillis() - 15.days.inWholeMilliseconds
        age(songId, "online", 15.days.inWholeMilliseconds)

        provider.result = ProviderResult.Miss
        val result = service.findLyrics(songId)

        assertEquals(LyricsSyncLevel.LINE, result.candidates.single().syncLevel)
        assertTrue(fetchedAt(songId, "online") > old + 1.days.inWholeMilliseconds)
    }

    private fun status(songId: UUID, provider: String): LyricsCandidateStatus = transaction(database) {
        LyricsCandidateTable.selectAll()
            .where { LyricsCandidateTable.songId eq songId and (LyricsCandidateTable.provider eq provider) }
            .single()[LyricsCandidateTable.status]
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `misses are retried after 14 days and errors are stored and retried after 3 days`(dialect: DbDialect): Unit = runBlocking {
        val missing = FakeProvider("missing", ProviderResult.Miss)
        val broken = FakeProvider("broken", ProviderResult.Error(IllegalStateException("down")))
        setup(dialect, missing, broken)
        val songId = song()

        val result = service.findLyrics(songId)
        service.findLyrics(songId)

        assertTrue(result.candidates.isEmpty())
        assertTrue(result.pending.isEmpty())
        assertEquals(1, missing.calls)
        assertEquals(1, broken.calls)
        transaction(database) {
            val rows = LyricsCandidateTable.selectAll().associate { it[LyricsCandidateTable.provider] to it }
            assertEquals(setOf("missing", "broken"), rows.keys)
            assertEquals(LyricsCandidateStatus.MISS, rows.getValue("missing")[LyricsCandidateTable.status])
            rows.getValue("broken").let {
                assertEquals(LyricsCandidateStatus.ERROR, it[LyricsCandidateTable.status])
                assertEquals(LyricsSyncLevel.PLAIN, it[LyricsCandidateTable.syncLevel])
                assertEquals(LyricsMatch.PROVIDER, it[LyricsCandidateTable.match])
                assertNull(it[LyricsCandidateTable.content])
                assertNull(it[LyricsCandidateTable.plain])
            }
        }
        assertTrue(service.songsDue().isEmpty())

        age(songId, "broken", 2.days.inWholeMilliseconds)
        assertTrue(service.songsDue().isEmpty())
        service.findLyrics(songId)
        assertEquals(1, broken.calls)

        age(songId, "broken", 4.days.inWholeMilliseconds)
        assertEquals(listOf(songId), service.songsDue())
        val old = System.currentTimeMillis() - 4.days.inWholeMilliseconds
        service.findLyrics(songId)
        assertEquals(2, broken.calls)
        assertEquals(1, missing.calls)
        assertEquals(LyricsCandidateStatus.ERROR, status(songId, "broken"))
        assertTrue(fetchedAt(songId, "broken") > old + 1.days.inWholeMilliseconds)
        assertTrue(service.songsDue().isEmpty())

        age(songId, "broken", 4.days.inWholeMilliseconds)
        broken.result = ProviderResult.Miss
        service.findLyrics(songId)
        assertEquals(LyricsCandidateStatus.MISS, status(songId, "broken"))

        age(songId, "missing", 15.days.inWholeMilliseconds)
        missing.result = found(lineLrc)
        val retried = service.findLyrics(songId)
        assertEquals(2, missing.calls)
        assertEquals(listOf("missing"), retried.candidates.map { it.provider.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an error turns a miss into an error row that a later answer replaces`(dialect: DbDialect): Unit = runBlocking {
        val provider = FakeProvider("online", ProviderResult.Miss)
        setup(dialect, provider)
        val songId = song()
        service.findLyrics(songId)
        val missId = transaction(database) { LyricsCandidateTable.selectAll().single()[LyricsCandidateTable.id].value }

        age(songId, "online", 15.days.inWholeMilliseconds)
        provider.result = ProviderResult.Error(IllegalStateException("down"))
        service.findLyrics(songId)
        assertEquals(2, provider.calls)
        assertEquals(LyricsCandidateStatus.ERROR, status(songId, "online"))
        assertEquals(missId, transaction(database) { LyricsCandidateTable.selectAll().single()[LyricsCandidateTable.id].value })

        age(songId, "online", 4.days.inWholeMilliseconds)
        provider.result = found(wordLrc)
        val result = service.findLyrics(songId)
        assertEquals(3, provider.calls)
        assertEquals(LyricsCandidateStatus.FOUND, status(songId, "online"))
        assertEquals(missId, result.candidates.single().id)
        assertEquals(LyricsSyncLevel.WORD, result.candidates.single().syncLevel)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an error keeps a found candidate and only refreshes its fetch time`(dialect: DbDialect): Unit = runBlocking {
        val provider = FakeProvider("online", found(lineLrc))
        setup(dialect, provider)
        val songId = song()
        val first = service.findLyrics(songId).candidates.single()
        val stored = assertNotNull(service.getLyrics(songId, first.id))

        age(songId, "online", 15.days.inWholeMilliseconds)
        val old = System.currentTimeMillis() - 15.days.inWholeMilliseconds
        provider.result = ProviderResult.Error(IllegalStateException("down"))
        val upgrade = service.findLyrics(songId)
        assertEquals(2, provider.calls)
        assertEquals(LyricsCandidateStatus.FOUND, status(songId, "online"))
        assertTrue(fetchedAt(songId, "online") > old + 1.days.inWholeMilliseconds)
        assertEquals(listOf(first.id), upgrade.candidates.map { it.id })
        assertTrue(service.songsDue().isEmpty())

        val refreshed = service.findLyrics(songId, refresh = true)
        assertEquals(3, provider.calls)
        assertEquals(listOf(first.id), refreshed.candidates.map { it.id })
        val kept = assertNotNull(service.getLyrics(songId, first.id))
        assertEquals(LyricsSyncLevel.LINE, kept.syncLevel)
        assertEquals(stored.plain, kept.plain)
        assertEquals(stored.lyrics, kept.lyrics)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `error rows are never candidates, ranked, pinned or counted`(dialect: DbDialect): Unit = runBlocking {
        val online = FakeProvider("online", found(wordLrc))
        val broken = FakeProvider("broken", ProviderResult.Error(IllegalStateException("down")))
        setup(dialect, online, broken)
        val songId = song(lyrics = lineLrc)

        val result = service.findLyrics(songId)
        val errorId = transaction(database) {
            LyricsCandidateTable.selectAll()
                .where { LyricsCandidateTable.songId eq songId and (LyricsCandidateTable.provider eq "broken") }
                .single().let {
                    assertEquals(LyricsCandidateStatus.ERROR, it[LyricsCandidateTable.status])
                    assertEquals(0, it[LyricsCandidateTable.rank])
                    it[LyricsCandidateTable.id].value
                }
        }

        val expected = listOf("online", LyricsCandidateService.SONG)
        assertEquals(expected, result.candidates.map { it.provider.id })
        assertEquals(listOf(1, 2), result.candidates.map { it.rank })
        assertEquals(expected, service.getLyricsCandidates(songId, includeContent = true).candidates.map { it.provider.id })
        assertEquals("online", service.getLyrics(songId)?.provider?.id)
        assertNull(service.getLyrics(songId, errorId))
        assertNull(service.getLyricsCandidate(errorId))
        assertThrows<IllegalArgumentException> { service.setPreferredLyrics(songId, errorId) }
        service.lyricsInfoFor(listOf(songId)).getValue(songId).let {
            assertEquals(2, it.candidateCount)
            assertEquals(setOf(LyricsSyncLevel.WORD, LyricsSyncLevel.LINE), it.levels)
            assertEquals("online", it.defaultProvider)
        }
        assertEquals(expected, service.streamLyrics(songId).toList().map { it.provider.id })
        verify(exactly = 1) { entityEvents.updated(EntityType.SONG, listOf(songId)) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an interactive lookup leaves a slow provider pending and a lookup without deadline stores it`(dialect: DbDialect): Unit = runTest {
        val slow = SlowProvider("slow", 13.seconds, found(wordLrc))
        setup(dialect, slow)
        val songId = song()

        val interactive = service.findLyrics(songId)
        assertEquals(listOf("slow"), interactive.pending)
        assertTrue(interactive.candidates.isEmpty())
        transaction(database) { assertEquals(0L, LyricsCandidateTable.selectAll().count()) }

        val start = currentTime
        val waited = service.findLyrics(songId, timeout = null)
        assertTrue(currentTime - start >= 13.seconds.inWholeMilliseconds)
        assertTrue(waited.pending.isEmpty())
        assertEquals(listOf("slow"), waited.candidates.map { it.provider.id })
        assertEquals(LyricsCandidateStatus.FOUND, status(songId, "slow"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getLyrics returns the song candidate for a song with only its own lyrics`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val synced = song(lyrics = lineLrc)
        val plain = song(lyrics = "Just words\nNo timing")

        val syncedCandidate = assertNotNull(service.getLyrics(synced))
        assertEquals(LyricsCandidateService.SONG, syncedCandidate.provider.id)
        assertEquals(LyricsSyncLevel.LINE, syncedCandidate.syncLevel)
        assertEquals(listOf("Hello there", "General words"), syncedCandidate.lyrics?.lines?.map { it.text })
        assertEquals(syncedCandidate, service.getLyricsCandidate(syncedCandidate.id))

        val plainCandidate = assertNotNull(service.getLyrics(plain))
        assertEquals(LyricsSyncLevel.PLAIN, plainCandidate.syncLevel)
        assertEquals("Just words\nNo timing", plainCandidate.plain)
        assertNull(plainCandidate.lyrics)
        assertNotEquals(syncedCandidate.id, plainCandidate.id)
        assertEquals(plainCandidate.id, service.getLyrics(plain)?.id)
        assertThrows<IllegalArgumentException> { service.setPreferredLyrics(plain, plainCandidate.id) }
    }

    @Serializable
    private data class OldWord(val text: String, val startTime: Long, val endTime: Long)

    @Serializable
    private data class OldLine(val startTime: Long, val endTime: Long, val words: List<OldWord>)

    @Serializable
    private data class OldSyncedLyrics(val lines: List<OldLine>)

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `old CBOR in synced_lyrics still decodes as the whisperx candidate`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = song()
        val old = OldSyncedLyrics(listOf(OldLine(1000, 2000, listOf(OldWord("Hello", 1000, 1400), OldWord("there", 1400, 2000)))))
        transaction(database) {
            SyncedLyricsTable.insert {
                it[SyncedLyricsTable.songId] = songId
                it[content] = AppCbor.encodeToByteArray(old)
            }
        }

        val candidate = assertNotNull(service.getLyrics(songId))

        assertEquals(LyricsCandidateService.WHISPERX, candidate.provider.id)
        assertEquals(LyricsSyncLevel.WORD, candidate.syncLevel)
        assertEquals("Hello there", candidate.plain)
        val line = assertNotNull(candidate.lyrics).lines.single()
        assertNull(line.text)
        assertFalse(line.background)
        assertEquals(listOf("Hello", "there"), line.words.map { it.text })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `lyricsInfoFor summarizes a batch of songs`(dialect: DbDialect) = runBlocking {
        setup(dialect, FakeProvider("online", found(wordLrc)))
        val withCandidates = song(lyrics = lineLrc)
        val plainOnly = song(lyrics = "only words")
        val nothing = song()
        val word = service.findLyrics(withCandidates).candidates.first()

        val info = service.lyricsInfoFor(listOf(withCandidates, plainOnly, nothing, UUID.randomUUID()))

        assertEquals(setOf(withCandidates, plainOnly, nothing), info.keys)
        info.getValue(withCandidates).let {
            assertTrue(it.available)
            assertEquals(LyricsSyncLevel.WORD, it.syncLevel)
            assertEquals(setOf(LyricsSyncLevel.WORD, LyricsSyncLevel.LINE), it.levels)
            assertEquals(word.id, it.defaultCandidateId)
            assertEquals("online", it.defaultProvider)
            assertEquals(2, it.candidateCount)
        }
        info.getValue(plainOnly).let {
            assertTrue(it.available)
            assertEquals(LyricsSyncLevel.PLAIN, it.syncLevel)
            assertEquals(LyricsCandidateService.SONG, it.defaultProvider)
            assertEquals(service.getLyrics(plainOnly)?.id, it.defaultCandidateId)
        }
        info.getValue(nothing).let {
            assertFalse(it.available)
            assertNull(it.syncLevel)
            assertTrue(it.levels.isEmpty())
            assertEquals(0, it.candidateCount)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `findLyricsByQuery stores nothing`(dialect: DbDialect) = runBlocking {
        setup(dialect, FakeProvider("online", found(wordLrc)))

        val result = service.findLyricsByQuery("Hello World", "The Band", durationMs = 200_000)

        assertEquals(listOf("online"), result.candidates.map { it.provider.id })
        assertNull(result.candidates.single().songId)
        assertEquals(result.candidates.single().id, result.defaultCandidateId)
        transaction(database) { assertEquals(0L, LyricsCandidateTable.selectAll().count()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `streamLyrics emits stored candidates and new answers and stores them`(dialect: DbDialect) = runBlocking {
        val online = FakeProvider("online", found(wordLrc))
        setup(dialect, online)
        val songId = song(lyrics = lineLrc)

        val emitted = service.streamLyrics(songId).toList()

        assertEquals(setOf(LyricsCandidateService.SONG, "online"), emitted.map { it.provider.id }.toSet())
        assertEquals(LyricsSyncLevel.WORD, emitted.single { it.provider.id == "online" }.syncLevel)
        assertEquals(
            listOf("online", LyricsCandidateService.SONG),
            service.getLyricsCandidates(songId).candidates.map { it.provider.id }
        )
        assertEquals(1, online.calls)
    }
}
