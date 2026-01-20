package dev.dertyp.services.tdn

import dev.dertyp.Indexer
import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import dev.dertyp.services.StorageService
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class TiddlService(indexer: Indexer, storageService: StorageService) : BaseDownloader(indexer, storageService) {
    override val loginCommand: MutableList<String> = mutableListOf("tiddl", "auth", "login")
    override val downloadCommand: MutableList<String> = mutableListOf("tiddl", "download", "url")
    override val favDownloadCommand: MutableList<String> = mutableListOf("tdn", "download", "fav", "-t")

    override fun authorizedCheck(result: ProcessExecutionResult) = result.fullOutput.contains("Already logged in.")

    override fun parseFavType(favType: TidalFavType): String = when (favType) {
        TidalFavType.tracks -> "track"
        TidalFavType.artists -> "artist"
        TidalFavType.albums -> "album"
        TidalFavType.videos -> "video"
    }

    private val tiddlPath = findInPath("tiddl")

    override suspend fun executeDownloader(
        command: Collection<String>,
        aliveCheck: suspend () -> Boolean,
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

        return executeCommand(cmd, aliveCheck, logger, onLineReceived)
    }
}