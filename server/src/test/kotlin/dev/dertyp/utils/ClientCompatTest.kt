package dev.dertyp.utils

import dev.dertyp.core.ClientInfo
import dev.dertyp.data.ApiVersion
import dev.dertyp.data.NowPlaying
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PlaybackState
import dev.dertyp.data.Song
import dev.dertyp.data.UserSong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ClientCompatTest {

    interface SongApi {
        suspend fun one(): UserSong?
        suspend fun oneSuspending(): UserSong?
        suspend fun list(): List<UserSong>
        suspend fun page(): PaginatedResponse<UserSong>
        suspend fun map(): Map<String, UserSong?>
        suspend fun nowPlaying(): NowPlaying
        suspend fun queueEntry(): PlaybackState.QueueEntry.Explicit
        suspend fun plain(): Song
        suspend fun text(): String
        suspend fun failing(): UserSong?
        fun flow(): Flow<Song>
    }

    private val userSong = UserSong(id = UUID.randomUUID(), title = "Title", artists = emptyList(), album = null, duration = 1000, explicit = false, path = "path")
    private val song = Song(id = UUID.randomUUID(), title = "Title", artists = emptyList(), album = null, duration = 1000, explicit = false, path = "path")

    private val fake = object : SongApi {
        override suspend fun one() = userSong
        override suspend fun oneSuspending(): UserSong? { yield(); return userSong }
        override suspend fun list() = listOf(userSong, userSong)
        override suspend fun page() = PaginatedResponse(listOf(userSong), page = 2, total = 10)
        override suspend fun map() = mapOf("a" to userSong, "b" to null)
        override suspend fun nowPlaying() = NowPlaying(userSong, 42)
        override suspend fun queueEntry() = PlaybackState.QueueEntry.Explicit(userSong, 7)
        override suspend fun plain() = song
        override suspend fun text() = "unchanged"
        override suspend fun failing(): UserSong? = throw IllegalStateException("boom")
        override fun flow() = flowOf(song, song)
    }

    private class BlankTitleShaper : ResponseShaper(ClientInfo.LEGACY) {
        override val isNoop: Boolean get() = false
        override fun shapeSong(song: Song) = song.copy(title = "")
        override fun shapeUserSong(song: UserSong) = song.copy(title = "")
    }

    private val wrapped = fake.withClientCompat(SongApi::class.java, BlankTitleShaper())

    @Test
    fun `shapes every supported return shape`() = runBlocking {
        assertEquals("", wrapped.one()!!.title)
        assertEquals("", wrapped.oneSuspending()!!.title)
        assertEquals(listOf("", ""), wrapped.list().map { it.title })
        val page = wrapped.page()
        assertEquals("", page.data.single().title)
        assertEquals(2, page.page)
        assertEquals(10, page.total)
        val map = wrapped.map()
        assertEquals("", map["a"]!!.title)
        assertEquals(null, map["b"])
        val nowPlaying = wrapped.nowPlaying()
        assertEquals("", nowPlaying.song.title)
        assertEquals(42, nowPlaying.startedAt)
        val entry = wrapped.queueEntry()
        assertEquals("", entry.song.title)
        assertEquals(7, entry.queueId)
        assertEquals("", wrapped.plain().title)
        assertEquals(listOf("", ""), wrapped.flow().toList().map { it.title })
    }

    @Test
    fun `leaves unrelated values and exceptions untouched`() = runBlocking {
        assertEquals("unchanged", wrapped.text())
        val e = assertThrows<IllegalStateException> { runBlocking { wrapped.failing() } }
        assertEquals("boom", e.message)
    }

    @Test
    fun `current clients are not proxied`() {
        val current = fake.withClientCompat(SongApi::class.java, ResponseShaper(ClientInfo(ApiVersion.CURRENT)))
        assertSame(fake, current)
        assertNotSame(fake, wrapped)
    }

    @Test
    fun `atmos path is hidden from clients below api version 3`() = runBlocking {
        val atmosUserSong = userSong.copy(atmosPath = "atmos.m4a")
        val atmosSong = song.copy(atmosPath = "atmos.m4a")
        val api = object : SongApi by fake {
            override suspend fun one() = atmosUserSong
            override suspend fun plain() = atmosSong
        }

        val legacy = api.withClientCompat(SongApi::class.java, ResponseShaper(ClientInfo(2)))
        assertEquals(null, legacy.one()!!.atmosPath)
        assertEquals(null, legacy.plain().atmosPath)
        assertEquals("Title", legacy.one()!!.title)

        val current = api.withClientCompat(SongApi::class.java, ResponseShaper(ClientInfo(ApiVersion.CURRENT)))
        assertEquals("atmos.m4a", current.one()!!.atmosPath)
        assertEquals("atmos.m4a", current.plain().atmosPath)
    }

    @Test
    fun `default shaper is identity`() = runBlocking {
        val shaper = object : ResponseShaper(ClientInfo.LEGACY) {
            override val isNoop: Boolean get() = false
        }
        val identity = fake.withClientCompat(SongApi::class.java, shaper)
        assertEquals(userSong, identity.one())
        assertEquals("Title", identity.nowPlaying().song.title)
    }
}
