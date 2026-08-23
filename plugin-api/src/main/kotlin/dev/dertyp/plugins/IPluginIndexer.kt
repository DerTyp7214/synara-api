package dev.dertyp.plugins

import dev.dertyp.PlatformUUID
import dev.dertyp.services.import.ImportBackend
import kotlinx.coroutines.Deferred
import java.nio.file.Path
import kotlin.io.path.extension

interface IPluginIndexer {
    val id: String
    val name: String
    val enabled: Boolean get() = true
    val importBackends: List<ImportBackend> get() = listOf(ImportBackend(id))

    fun canHandle(path: Path): Boolean

    suspend fun queue(
        songPaths: List<Path>,
        playlistPaths: List<Path>,
        type: String? = null,
        userId: PlatformUUID? = null,
        stdout: suspend (String) -> Unit
    ): Deferred<Unit>

    suspend fun start(
        userId: PlatformUUID? = null,
        stdout: suspend (String) -> Unit
    )

    val audioExtension: String
    val audioExtensions: Set<String> get() = setOf(audioExtension)
    fun isAudio(path: Path): Boolean = path.extension.lowercase() in audioExtensions
    val playlistExtension: String
    val artistDelimiter: String get() = ";"
}
