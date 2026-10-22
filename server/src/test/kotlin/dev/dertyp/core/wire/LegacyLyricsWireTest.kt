@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core.wire

import dev.dertyp.core.ClientInfo
import dev.dertyp.core.ServerWire
import dev.dertyp.core.LegacyServerCbor
import dev.dertyp.core.LegacyServerJson
import dev.dertyp.core.jsonFor
import dev.dertyp.core.wire.fixtures.LegacyLyricsFixture
import dev.dertyp.core.wireFor
import dev.dertyp.data.EntityType
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PlaybackState
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.Song
import dev.dertyp.data.SongLyricsInfo
import dev.dertyp.data.UserSong
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.models.LyricsSyncLevel
import dev.dertyp.utils.ResponseShaper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

@Serializable
private data class LyricsView(val lyrics: String? = null, val lyricsInfo: SongLyricsInfo? = null)

class LegacyLyricsWireTest {
    private val stored = "[00:01.00] First line\n[00:02.50] Second line"
    private val info = SongLyricsInfo(
        available = true,
        syncLevel = LyricsSyncLevel.LINE,
        levels = setOf(LyricsSyncLevel.PLAIN, LyricsSyncLevel.LINE),
        candidateCount = 2,
    )
    private val oldVersions = listOf(1, 7, 9, 10)
    private val allVersions = oldVersions + 11

    private lateinit var fixture: LegacyLyricsFixture
    private lateinit var song: Song
    private lateinit var userSong: UserSong

    @BeforeEach
    fun setUp() {
        fixture = LegacyLyricsFixture()
        val id = fixture.insertSong(stored)
        song = Song(
            id = id,
            title = "Song",
            artists = emptyList(),
            album = null,
            duration = 1000,
            explicit = false,
            path = "/song.flac",
            lyricsInfo = info,
        )
        userSong = UserSong(
            id = id,
            title = "Song",
            artists = emptyList(),
            album = null,
            duration = 1000,
            explicit = false,
            path = "/song.flac",
            isFavourite = true,
            lyricsInfo = info,
        )
    }

    @AfterEach
    fun tearDown() {
        fixture.close()
    }

    private fun <T : Any> shaped(version: Int, value: T): T =
        value.javaClass.cast(runBlocking { ResponseShaper(ClientInfo(version)).shape(value) })

    private fun <T : Any> jsonObject(version: Int, serializer: KSerializer<T>, value: T): JsonObject {
        val text = jsonFor(ClientInfo(version)).encodeToString(serializer, shaped(version, value))
        return AppJson.parseToJsonElement(text).jsonObject
    }

    private fun <T : Any> cborBytes(version: Int, serializer: KSerializer<T>, value: T): ByteArray {
        val shapedValue = shaped(version, value)
        val served = wireFor(ClientInfo(version)) ?: return AppCbor.encodeToByteArray(serializer, shapedValue)
        return LegacyCborFormat(served.cbor, served.wire).encodeToByteArray(serializer, shapedValue)
    }

    @Test
    fun `clients up to api version 10 get the stored lyrics and no lyrics info in json`() {
        oldVersions.forEach { version ->
            listOf(
                jsonObject(version, Song.serializer(), song),
                jsonObject(version, UserSong.serializer(), userSong),
            ).forEach { body ->
                assertEquals(JsonPrimitive(stored), body["lyrics"], "version $version")
                assertFalse("lyricsInfo" in body, "version $version")
            }
        }
    }

    @Test
    fun `clients from api version 11 get lyrics info and no lyrics key in json`() {
        listOf(
            jsonObject(11, Song.serializer(), song),
            jsonObject(11, UserSong.serializer(), userSong),
        ).forEach { body ->
            assertFalse("lyrics" in body)
            assertEquals(AppJson.encodeToJsonElement(SongLyricsInfo.serializer(), info), body["lyricsInfo"])
        }
        assertFalse("lyricsInfo" in jsonObject(11, Song.serializer(), song.copy(lyricsInfo = null)))
    }

    @Test
    fun `cbor carries the stored lyrics up to api version 10 and lyrics info from 11`() {
        oldVersions.forEach { version ->
            listOf(cborBytes(version, Song.serializer(), song), cborBytes(version, UserSong.serializer(), userSong))
                .forEach { bytes ->
                    assertEquals(LyricsView(lyrics = stored), AppCbor.decodeFromByteArray(LyricsView.serializer(), bytes))
                }
        }
        listOf(cborBytes(11, Song.serializer(), song), cborBytes(11, UserSong.serializer(), userSong)).forEach { bytes ->
            assertEquals(LyricsView(lyricsInfo = info), AppCbor.decodeFromByteArray(LyricsView.serializer(), bytes))
        }
    }

    @Test
    fun `a paginated response carries the full lyrics of every song for old clients`() {
        val otherLyrics = "Another text\nwith two lines"
        val other = userSong.copy(id = fixture.insertSong(otherLyrics), title = "Other")
        val page = PaginatedResponse(listOf(userSong, other), total = 2)
        val serializer = PaginatedResponse.serializer(UserSong.serializer())

        val json = jsonObject(10, serializer, page)
        val songs = AppJson.decodeFromJsonElement(ListSerializer(JsonObject.serializer()), json.getValue("data"))
        assertEquals(listOf(JsonPrimitive(stored), JsonPrimitive(otherLyrics)), songs.map { it["lyrics"] })
        assertTrue(songs.none { "lyricsInfo" in it })

        val bytes = cborBytes(10, serializer, page)
        val views = AppCbor.decodeFromByteArray(PaginatedResponse.serializer(LyricsView.serializer()), bytes)
        assertEquals(listOf(stored, otherLyrics), views.data.map { it.lyrics })
    }

    @Test
    fun `the shaper prefetches the lyrics that the encoder writes and song changes invalidate them`() {
        val shapedSong = shaped(10, song)
        fixture.setLyrics(song.id, "Changed without an event")
        val wire = jsonFor(ClientInfo(10))
        val cached = AppJson.parseToJsonElement(wire.encodeToString(Song.serializer(), shapedSong)).jsonObject
        assertEquals(JsonPrimitive(stored), cached["lyrics"])

        fixture.cache.updated(EntityType.SONG, listOf(song.id))
        val fresh = AppJson.parseToJsonElement(wire.encodeToString(Song.serializer(), shapedSong)).jsonObject
        assertEquals(JsonPrimitive("Changed without an event"), fresh["lyrics"])
    }

    @Test
    fun `a song the shaper never saw is loaded on encode and a missing song has empty lyrics`() {
        val wire = jsonFor(ClientInfo(10))
        val direct = AppJson.parseToJsonElement(wire.encodeToString(Song.serializer(), song.copy(lyricsInfo = null)))
        assertEquals(JsonPrimitive(stored), direct.jsonObject["lyrics"])
        val unknown = song.copy(id = UUID.randomUUID(), lyricsInfo = null)
        val missing = AppJson.parseToJsonElement(wire.encodeToString(Song.serializer(), unknown))
        assertEquals(JsonPrimitive(""), missing.jsonObject["lyrics"])
    }

    @Test
    fun `songs that send lyrics back are decoded for every api version`() {
        val plain = song.copy(lyricsInfo = null)
        val withLyrics = JsonObject(
            AppJson.encodeToJsonElement(Song.serializer(), plain).jsonObject + ("lyrics" to JsonPrimitive("Sent back"))
        )
        val text = AppJson.encodeToString(JsonObject.serializer(), withLyrics)
        val bytes = cborBytes(10, Song.serializer(), plain)
        allVersions.forEach { version ->
            val wire = jsonFor(ClientInfo(version))
            assertEquals(plain, wire.decodeFromString(Song.serializer(), text), "version $version")
            assertEquals(plain, wire.decodeFromJsonElement(Song.serializer(), withLyrics), "version $version")
            val served = wireFor(ClientInfo(version))
            val decoded = if (served == null) {
                AppCbor.decodeFromByteArray(Song.serializer(), bytes)
            } else {
                LegacyCborFormat(served.cbor, served.wire).decodeFromByteArray(Song.serializer(), bytes)
            }
            assertEquals(plain, decoded, "version $version")
        }
    }

    @Test
    fun `clients below api version 8 get the renames wire plus the lyrics field`() {
        val renamesOnly = WireJson(LegacyServerJson, ServerWire)
        val plain = song.copy(lyricsInfo = null)
        val legacy = AppJson.parseToJsonElement(jsonFor(ClientInfo(7)).encodeToString(Song.serializer(), plain)).jsonObject
        val renamed = AppJson.parseToJsonElement(renamesOnly.encodeToString(Song.serializer(), plain)).jsonObject
        assertEquals(renamed, JsonObject(legacy.filterKeys { it != "lyrics" }))
        assertEquals(JsonPrimitive(stored), legacy["lyrics"])

        val state = PlaybackState(
            queue = listOf(PlaybackState.QueueEntry.WithSong(userSong.copy(lyricsInfo = null), 1)),
            currentIndex = 0,
            isPlaying = true,
            positionMs = 5,
            isShuffled = true,
            repeatMode = RepeatMode.ALL,
        )
        val served = wireFor(ClientInfo(7))!!
        val bytes = LegacyCborFormat(served.cbor, served.wire).encodeToByteArray(PlaybackState.serializer(), state)
        assertEquals(state, AppCbor.decodeFromByteArray(PlaybackState.serializer(), bytes))
        val noSongs = state.copy(queue = listOf(PlaybackState.QueueEntry.FromSource(song.id, 1)))
        assertArrayEquals(
            LegacyCborFormat(LegacyServerCbor, ServerWire).encodeToByteArray(PlaybackState.serializer(), noSongs),
            LegacyCborFormat(served.cbor, served.wire).encodeToByteArray(PlaybackState.serializer(), noSongs),
        )
    }

    @Test
    fun `one wire is cached per set of active legacy rules`() {
        assertSame(wireFor(ClientInfo.LEGACY), wireFor(ClientInfo(7)))
        assertSame(wireFor(ClientInfo(8)), wireFor(ClientInfo(10)))
        assertNotSame(wireFor(ClientInfo(7)), wireFor(ClientInfo(8)))
        assertNotNull(wireFor(ClientInfo(10)))
        assertNull(wireFor(ClientInfo(11)))
    }
}
