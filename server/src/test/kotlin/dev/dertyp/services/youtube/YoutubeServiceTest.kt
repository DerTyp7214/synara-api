package dev.dertyp.services.youtube

import dev.dertyp.Indexer
import dev.dertyp.findInPath
import dev.dertyp.services.LrcLibService
import dev.dertyp.services.SongService
import dev.dertyp.services.StorageService
import dev.dertyp.services.UserPlaylistService
import dev.dertyp.services.download.DownloadService
import dev.dertyp.services.download.Type
import dev.dertyp.services.metadata.MusicBrainzService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest

class YoutubeServiceTest : KoinTest {

    private lateinit var service: YoutubeService
    private val indexer = mockk<Indexer>(relaxed = true)
    private val storageService = mockk<StorageService>(relaxed = true)
    private val youtubeApiService = mockk<YoutubeApiService>(relaxed = true)
    private val lrcLibService = mockk<LrcLibService>(relaxed = true)
    private val musicBrainzService = mockk<MusicBrainzService>(relaxed = true)
    
    private val songService = mockk<SongService>(relaxed = true)
    private val userPlaylistService = mockk<UserPlaylistService>(relaxed = true)
    private val downloadService = mockk<DownloadService>(relaxed = true)

    @BeforeEach
    fun setup() {
        startKoin {
            modules(module {
                single { songService }
                single { userPlaylistService }
                single { downloadService }
            })
        }

        mockkStatic("dev.dertyp.UtilsKt")
        every { findInPath("yt-dlp") } returns "/usr/bin/yt-dlp"

        service = YoutubeService(indexer, storageService, youtubeApiService, lrcLibService, musicBrainzService)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `parseUrl should handle various youtube links`() = runBlocking {
        assertEquals("abc" to Type.SONG, service.parseUrl("https://www.youtube.com/watch?v=abc"))
        assertEquals("abc" to Type.SONG, service.parseUrl("https://youtu.be/abc"))
        assertEquals("abc" to Type.SONG, service.parseUrl("https://music.youtube.com/watch?v=abc"))
        assertEquals("abc" to Type.SONG, service.parseUrl("https://m.youtube.com/watch?v=abc"))
        assertEquals("abc" to Type.SONG, service.parseUrl("https://www.youtube.com/shorts/abc"))
        assertEquals("abc" to Type.SONG, service.parseUrl("https://www.youtube.com/watch?v=abc&list=list123"))
        assertEquals("list123" to Type.PLAYLIST, service.parseUrl("https://www.youtube.com/playlist?list=list123"))
        assertEquals("@channel" to Type.ARTIST, service.parseUrl("https://www.youtube.com/@channel"))
        assertEquals("channel/UC123" to Type.ARTIST, service.parseUrl("https://www.youtube.com/channel/UC123"))
        assertEquals("user/username" to Type.ARTIST, service.parseUrl("https://www.youtube.com/user/username"))
    }

    @Test
    fun `enabled should depend on yt-dlp presence`() {
        every { findInPath("yt-dlp") } returns "/usr/bin/yt-dlp"
        assertTrue(service.enabled)

        every { findInPath("yt-dlp") } returns null
        val serviceNoYtdlp = YoutubeService(indexer, storageService, youtubeApiService, lrcLibService, musicBrainzService)
        assertFalse(serviceNoYtdlp.enabled)
    }
}
