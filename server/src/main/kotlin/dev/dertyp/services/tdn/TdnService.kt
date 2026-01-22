package dev.dertyp.services.tdn

import dev.dertyp.Indexer
import dev.dertyp.core.ClientCloseException
import dev.dertyp.core.isInside
import dev.dertyp.core.oneLine
import dev.dertyp.core.resolveRelativeAbsolute
import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import dev.dertyp.services.StorageService
import kotlinx.coroutines.delay
import java.nio.file.Path
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.deleteIfExists

@OptIn(ExperimentalAtomicApi::class)
class TdnService(indexer: Indexer, storageService: StorageService) : BaseDownloader(indexer, storageService) {
    override val loginCommand: MutableList<String> = mutableListOf("tdn", "login")
    override val downloadCommand: MutableList<String> = mutableListOf("tdn", "dl")
    override val favDownloadCommand: MutableList<String> = mutableListOf("tdn", "dl_fav")

    override fun authorizedCheck(result: ProcessExecutionResult) = result.fullOutput.contains("You are logged in.")

    override suspend fun handleErrors(
        command: Collection<String>,
        result: ProcessExecutionResult,
        currentTry: Int,
        maxRetries: Int,
        paths: MutableList<Path>,
        aliveCheck: suspend () -> Boolean,
        logProxy: suspend (String) -> Unit
    ): ProcessExecutionResult {
        if (storageService.playlistsPath == null) return result

        val pathAlternation =
            "(${storageService.playlistsPath}|${storageService.albumsPath})"

        val rootPath = Path(storageService.playlistsPath).parent.absolute()

        val errorRegex = Regex(
            "FileNotFoundError:\\s+(\\[.+?])\\s+No\\s+such\\s+file\\s+or\\s+directory:\\s+'" +
                    "(.+?)'\\s+->\\s+['\"]$pathAlternation/([^/]+?)/(.+?)['\"]"
        )

        val matchResult = errorRegex.find(result.fullOutput.oneLine())
        if (matchResult != null) {
            val relativePathString = matchResult.groupValues[2]
            val fullPathString =
                "${matchResult.groupValues[3]}/${matchResult.groupValues[4]}/${matchResult.groupValues[5]}"

            try {
                val fullPath = Path(fullPathString)
                val brokenFilePath = fullPath.resolveRelativeAbsolute(relativePathString)

                if (currentTry < maxRetries && brokenFilePath.isInside(rootPath)) {
                    if (brokenFilePath.deleteIfExists()) {
                        logProxy("Deleted file $brokenFilePath")
                        logger.info("Deleted file $brokenFilePath")
                    } else {
                        logProxy("Could not delete file $brokenFilePath")
                        logger.info("Could not delete file $brokenFilePath")
                    }

                    logProxy("Retrying (${currentTry + 1}/$maxRetries)")

                    (0 until 10).forEach { i ->
                        logProxy("Waiting for 500ms (${i + 1}/10)")
                        if (!aliveCheck()) throw ClientCloseException()
                        delay(500)
                    }
                } else if (!brokenFilePath.isInside(rootPath)) {
                    logProxy("File ($brokenFilePath) not inside $rootPath")
                }
            } catch (_: Throwable) {
            }
        } else logger.info(errorRegex.toString())

        val (newResult, newPaths) = collectDownloadedFiles(
            command,
            maxRetries,
            currentTry + 1,
            aliveCheck,
            logProxy
        )

        paths.addAll(newPaths)
        return newResult
    }

    private val tdnPath = findInPath("tdn")

    override suspend fun executeDownloader(
        command: Collection<String>,
        aliveCheck: suspend () -> Boolean,
        onLineReceived: suspend (String) -> Unit
    ): ProcessExecutionResult {
        val cmd = command.toMutableList()
        if (cmd.isEmpty() || (cmd[0] != "tdn" && cmd[0] != "python3")) {
            return ProcessExecutionResult(-1, "Error: Command must start with 'tdn'.", "")
        }

        if (tdnPath == null) {
            return ProcessExecutionResult(-1, "Error: The tdn path does not exist.", "")
        }

        if (cmd[0] != "python3") {
            cmd[0] = tdnPath
            cmd.add(0, "python3")
            cmd.add(1, "-u")
        }

        return executeCommand(cmd, aliveCheck, logger, onLineReceived)
    }
}