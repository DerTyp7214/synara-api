package dev.dertyp.services.download

import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class TidalUrlParsingTest {
    private val indexer = mockk<IPluginIndexer>(relaxed = true)
    private val storageService = mockk<IServerStorageService>(relaxed = true)

    private class TestTidalDownloader(
        indexer: IPluginIndexer,
        storageService: IServerStorageService
    ) : TidalBaseDownloader(indexer, storageService) {
        override val id: String = "test"
        override val enabled: Boolean = true
        override val loginCommand: MutableList<String> = mutableListOf()
        override val downloadCommand: MutableList<String> = mutableListOf()
        override val favDownloadCommand: MutableList<String> = mutableListOf()
        override fun authorizedCheck(result: ProcessExecutionResult): Boolean = true
        override fun tokenFileExists(): Boolean = true
        override fun canHandle(url: String): Boolean = true
        override suspend fun executeDownloader(
            command: Collection<String>,
            aliveCheck: suspend () -> Boolean,
            directory: File?,
            onLineReceived: suspend (String) -> Unit
        ): ProcessExecutionResult = ProcessExecutionResult(0, "", "")
    }

    private val downloader = TestTidalDownloader(indexer, storageService)

    @Test
    fun `parseUrl should handle various Tidal links`() = runBlocking {
        assertEquals("357676034" to Type.ALBUM, downloader.parseUrl("https://tidal.com/album/357676034"))
        assertEquals("130201923" to Type.ALBUM, downloader.parseUrl("https://tidal.com/browse/album/130201923"))
        assertEquals("11343637" to Type.ALBUM, downloader.parseUrl("https://listen.tidal.com/album/11343637"))
        assertEquals("301366648" to Type.ALBUM, downloader.parseUrl("https://listen.tidal.com/album/301366648/track/301366649"))
        assertEquals("80" to Type.ARTIST, downloader.parseUrl("https://tidal.com/artist/80"))
        assertEquals("3557299" to Type.ARTIST, downloader.parseUrl("https://tidal.com/browse/artist/3557299"))
        assertEquals("116" to Type.ARTIST, downloader.parseUrl("https://listen.tidal.com/artist/116"))
        assertEquals("196091131" to Type.SONG, downloader.parseUrl("https://tidal.com/track/196091131"))
        assertEquals("11343638" to Type.SONG, downloader.parseUrl("https://tidal.com/browse/track/11343638"))
        assertEquals("358461354" to Type.VIDEO, downloader.parseUrl("https://tidal.com/video/358461354"))
    }
}
