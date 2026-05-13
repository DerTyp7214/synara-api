package dev.dertyp.services.import

import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import java.io.File
import java.net.URI
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class TiddlService(
    indexer: IPluginIndexer,
    storageService: IServerStorageService
) : TidalBaseImporter(indexer, storageService) {
    override val id: String = ID
    override val enabled: Boolean get() = tiddlPath != null && tokenFileExists()

    override val loginCommand: MutableList<String> = mutableListOf("tiddl", "auth", "login", "--no-browser")
    override val importCommand: MutableList<String> = mutableListOf("tiddl", "download", "url")
    override val favImportCommand: MutableList<String> = mutableListOf("tiddl", "download", "fav", "--types")

    companion object {
        val ID = ImportBackend.Tiddl.id
    }

    override fun authorizedCheck(result: ProcessExecutionResult) = result.fullOutput.contains("Already logged in.")

    override fun canHandle(url: String): Boolean {
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: ""
            host == "tidal.com" || host.endsWith(".tidal.com") || url.startsWith("tiddl:")
        } catch (_: Exception) {
            url.startsWith("tiddl:")
        }
    }

    override fun parseFavType(favType: ImportFavType): String = when (favType) {
        ImportFavType.tracks -> "track"
        ImportFavType.artists -> "artist"
        ImportFavType.albums -> "album"
        ImportFavType.videos -> "video"
    }

    override fun tokenFileExists(): Boolean {
        val homeDir = System.getProperty("user.home")
        val tiddlTokenJson = File(homeDir, ".tiddl/auth.json")
        return tiddlTokenJson.exists()
    }

    private val tiddlPath = findInPath("tiddl")

    override suspend fun executeImporter(
        command: Collection<String>,
        aliveCheck: suspend () -> Boolean,
        directory: File?,
        onLineReceived: suspend (String) -> Unit
    ): ProcessExecutionResult {
        val cmd = command.toMutableList()
        if (cmd.isEmpty() || (cmd[0] != "tiddl" && cmd[0] != "python3")) {
            return ProcessExecutionResult(-1, "Error: Command must start with 'tiddl'.", "")
        }

        if (tiddlPath == null) {
            return ProcessExecutionResult(-1, "Error: The tiddl path does not exist.", "")
        }

        if (cmd[0] != "python3") {
            cmd[0] = tiddlPath
            cmd.add(0, "python3")
            cmd.add(1, "-u")
        }

        return executeCommand(cmd, aliveCheck, logger, directory, onLineReceived = onLineReceived)
    }
}
