package dev.dertyp.services.soundcloud

import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.services.LrcLibService
import dev.dertyp.services.SongService
import dev.dertyp.services.UserPlaylistService
import dev.dertyp.services.download.DownloadService
import dev.dertyp.services.download.Type
import dev.dertyp.services.metadata.MusicBrainzService
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest

class SoundcloudServiceTest : KoinTest {
    private lateinit var service: SoundcloudService
    private val indexer = mockk<IPluginIndexer>(relaxed = true)
    private val storageService = mockk<IServerStorageService>(relaxed = true)
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
        service = SoundcloudService(indexer, storageService, lrcLibService, musicBrainzService)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `parseUrl should handle various soundcloud links`() = runBlocking {
        assertEquals("user" to Type.ARTIST, service.parseUrl("https://soundcloud.com/user"))
        assertEquals("user" to Type.ARTIST, service.parseUrl("https://soundcloud.com/user/reposts"))
        assertEquals("user/track" to Type.SONG, service.parseUrl("https://soundcloud.com/user/track"))
        assertEquals("user/sets/playlist" to Type.PLAYLIST, service.parseUrl("https://soundcloud.com/user/sets/playlist"))
    }
}
