package dev.dertyp.services.subsonic

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.SongTable
import dev.dertyp.services.LyricsService
import dev.dertyp.services.SongService
import dev.dertyp.services.lyrics.LyricsCandidateService
import dev.dertyp.services.models.*
import dev.dertyp.testing.insertAlbum
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.Date
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class SubsonicLyricsRoutesTest {
    private lateinit var database: Database
    private val user = User(id = UUID.randomUUID(), username = "tester")
    private val authenticator = mockk<SubsonicAuthenticator>()
    private val songService = mockk<SongService>()
    private val lyricsService = mockk<LyricsService>()
    private val lyricsCandidateService = mockk<LyricsCandidateService>()

    @AfterEach
    fun tearDown() {
        runCatching { stopKoin() }
        TestDatabase.cleanUp()
    }

    private fun song(lyrics: String = ""): UUID {
        val songId = UUID.randomUUID()
        transaction(database) {
            val albumId = insertAlbum()
            SongTable.insert {
                it[id] = songId
                it[title] = "Lantern"
                it[SongTable.albumId] = albumId
                it[SongTable.lyrics] = lyrics
            }
        }
        coEvery { songService.byId(songId, user.id) } returns UserSong(
            id = songId,
            title = "Lantern",
            artists = listOf(ArtistCredit(UUID.randomUUID(), "The Band", isGroup = true)),
            album = null,
            duration = 200_000,
            explicit = false,
            path = "/music/$songId.flac",
        )
        return songId
    }

    private fun ApplicationTestBuilder.setUp(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "subsonic_lyrics_test", AlbumTable, SongTable)
        coEvery { authenticator.authenticate(any()) } returns SubsonicAuthResult.Ok(user)
        environment { config = MapApplicationConfig() }
        application {
            install(Koin) {
                modules(module {
                    single { authenticator }
                    single { songService }
                    single { lyricsService }
                    single { lyricsCandidateService }
                })
            }
            routing { route("/rest") { subsonicAnnotationRoutes() } }
        }
    }

    private suspend fun ApplicationTestBuilder.structuredLyrics(songId: UUID): JsonObject? {
        val body = client.get("/rest/getLyricsBySongId?id=${songId.trId()}&f=json").bodyAsText()
        val list = Json.parseToJsonElement(body).jsonObject["subsonic-response"]!!.jsonObject["lyricsList"]!!.jsonObject
        return list["structuredLyrics"]?.jsonArray?.singleOrNull()?.jsonObject
    }

    private fun JsonObject.lines(): List<Pair<Long?, String>> = this["line"]!!.jsonArray.map { line ->
        line.jsonObject["start"]?.jsonPrimitive?.long to line.jsonObject["value"]!!.jsonPrimitive.content
    }

    private fun word(text: String, start: Long, end: Long) = LyricWord(text, start.milliseconds, end.milliseconds)

    private fun candidate(songId: UUID, lyrics: SyncedLyrics) = LyricsCandidate(
        id = UUID.randomUUID(),
        songId = songId,
        provider = LyricsProviderInfo("betterlyrics", "Better Lyrics", null, enabled = true, priority = 0),
        syncLevel = LyricsSyncLevel.WORD,
        instrumental = false,
        match = LyricsMatch.VERIFIED,
        durationGapSec = 0,
        rank = 1,
        preferred = false,
        fetchedAt = Date(),
        plain = "",
        lyrics = lyrics,
    )

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `default synced candidate is served with syllables joined and background lines skipped`(dialect: DbDialect) = testApplication {
        setUp(dialect)
        val songId = song(lyrics = "Plain text")
        coEvery { lyricsCandidateService.getLyrics(songId) } returns candidate(
            songId,
            SyncedLyrics(
                listOf(
                    LyricLine(
                        1000.milliseconds, 2500.milliseconds,
                        listOf(word("Hel", 1000, 1300), word("lo ", 1300, 1600), word("world", 1600, 2500)),
                        text = "Hello world",
                    ),
                    LyricLine(
                        1800.milliseconds, 2400.milliseconds,
                        listOf(word("(yeah)", 1800, 2400)),
                        text = "(yeah)",
                        background = true,
                    ),
                    LyricLine(
                        3000.milliseconds, 4000.milliseconds,
                        listOf(word("second", 3000, 3500), word("line", 3500, 4000)),
                    ),
                )
            )
        )

        val structured = structuredLyrics(songId)!!

        assertEquals(true, structured["synced"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(1000L to "Hello world", 3000L to "second line"), structured.lines())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `transcribed lyrics are served when no candidate is synced`(dialect: DbDialect) = testApplication {
        setUp(dialect)
        val songId = song(lyrics = "Plain text")
        coEvery { lyricsCandidateService.getLyrics(songId) } returns null
        coEvery { lyricsService.getSyncedLyrics(songId) } returns SyncedLyrics(
            listOf(LyricLine(500.milliseconds, 1500.milliseconds, listOf(word("from", 500, 900), word("whisper", 900, 1500))))
        )

        val structured = structuredLyrics(songId)!!

        assertEquals(true, structured["synced"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(500L to "from whisper"), structured.lines())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `song lyrics are served unsynced without synced lyrics`(dialect: DbDialect) = testApplication {
        setUp(dialect)
        val songId = song(lyrics = "First line\nSecond line")
        coEvery { lyricsCandidateService.getLyrics(songId) } returns candidate(songId, SyncedLyrics(emptyList()))
        coEvery { lyricsService.getSyncedLyrics(songId) } returns null

        val structured = structuredLyrics(songId)!!

        assertEquals(false, structured["synced"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(null to "First line", null to "Second line"), structured.lines())
    }
}
