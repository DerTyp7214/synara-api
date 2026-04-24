package dev.dertyp.plugins

import dev.dertyp.PlatformUUID
import dev.dertyp.services.download.DownloadBackend
import kotlinx.coroutines.Deferred
import java.nio.file.Path

interface IPluginIndexer {
    val id: String
    val name: String
    val enabled: Boolean get() = true
    val downloadBackends: List<DownloadBackend> get() = listOf(DownloadBackend(id))

    fun canHandle(path: Path): Boolean

    suspend fun queue(
        songPaths: List<Path>,
        playlistPaths: List<Path>,
        type: String? = null,
        userId: PlatformUUID? = null,
        stdout: suspend (String) -> Unit
    ): Deferred<Unit>

    val audioExtension: String
    val playlistExtension: String
    val artistDelimiter: String get() = ";"
}
