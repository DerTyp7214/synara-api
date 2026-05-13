package dev.dertyp.services.import

import dev.dertyp.PlatformUUID
import dev.dertyp.core.ClientCloseException
import dev.dertyp.core.isInside
import dev.dertyp.core.oneLine
import dev.dertyp.core.resolveRelativeAbsolute
import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import kotlinx.coroutines.delay
import java.io.File
import java.net.URI
import java.nio.file.Path
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.deleteIfExists
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalAtomicApi::class)
class TdnService(
    indexer: IPluginIndexer,
    storageService: IServerStorageService
) : TidalBaseImporter(indexer, storageService) {
    override val id: String = ID
    override val enabled: Boolean get() = tdnPath != null && tokenFileExists()

    override val loginCommand: MutableList<String> = mutableListOf("tdn", "login")
    override val importCommand: MutableList<String> = mutableListOf("tdn", "dl")
    override val favImportCommand: MutableList<String> = mutableListOf("tdn", "dl_fav")

    companion object {
        val ID = ImportBackend.Tdn.id
    }

    override fun authorizedCheck(result: ProcessExecutionResult) = result.fullOutput.contains("You are logged in.")

    override fun canHandle(url: String): Boolean {
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: ""
            host == "tidal.com" || host.endsWith(".tidal.com") || url.startsWith("tdn:")
        } catch (_: Exception) {
            url.startsWith("tdn:")
        }
    }

    override suspend fun handleErrors(
        command: Collection<String>,
        result: ProcessExecutionResult,
        currentTry: Int,
        maxRetries: Int,
        paths: MutableList<Path>,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID?,
        logProxy: suspend (String) -> Unit
    ): ProcessExecutionResult {
        if (pluginStorage.playlistsPath == null) return result

        val pathAlternation =
            "(${pluginStorage.playlistsPath}|${pluginStorage.albumsPath})"

        val rootPath = Path(pluginStorage.playlistsPath!!).parent.absolute()

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
                        delay(500.milliseconds)
                    }
                } else if (!brokenFilePath.isInside(rootPath)) {
                    logProxy("File ($brokenFilePath) not inside $rootPath")
                }
            } catch (_: Throwable) {
            }
        } else logger.info(errorRegex.toString())

        val (newResult, newPaths) = collectImportedFiles(
            command,
            maxRetries,
            currentTry + 1,
            aliveCheck,
            userId,
            logProxy
        )

        paths.addAll(newPaths)
        return newResult
    }

    override fun tokenFileExists(): Boolean {
        val homeDir = System.getProperty("user.home")
        val tdnTokenJson = File(homeDir, ".config/tidal_dl_ng/token.json")
        return tdnTokenJson.exists()
    }

    private val tdnPath = findInPath("tdn")

    override suspend fun executeImporter(
        command: Collection<String>,
        aliveCheck: suspend () -> Boolean,
        directory: File?,
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

        directory?.mkdirs()

        return executeCommand(cmd, aliveCheck, logger, directory, onLineReceived = onLineReceived)
    }
}
