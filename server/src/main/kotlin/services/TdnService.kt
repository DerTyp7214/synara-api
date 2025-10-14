package dev.dertyp.services

import dev.dertyp.Indexer
import dev.dertyp.core.*
import kotlinx.coroutines.*
import java.io.InputStreamReader
import java.nio.file.Path
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.*

@Suppress("EnumEntryName")
enum class TdnFavoriteType {
    tracks,
    artists,
    albums,
    videos
}

data class ProcessExecutionResult(val exitCode: Int, val fullOutput: String, val error: String)

@OptIn(ExperimentalAtomicApi::class)
class TdnService(private val indexer: Indexer) : Service() {
    val isDownloadActive = AtomicBoolean(false)

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun collectDownloadedFiles(
        command: List<String>,
        maxRetries: Int = 5,
        currentTry: Int = 0,
        aliveCheck: suspend () -> Boolean,
        logProxy: suspend (String) -> Unit
    ): Pair<ProcessExecutionResult, List<Path>> {
        val pathLines = mutableListOf<String>()
        var result = executeTdn(command, aliveCheck) {
            if (!aliveCheck()) throw ClientCloseException()
            val trimmed = it.trim().removeSurrounding("'")
            if (indexer.tracksPath != null) {
                if (
                    trimmed.endsWith(indexer.audioExtension) &&
                    trimmed.contains(indexer.tracksPath)
                ) {
                    val start = trimmed.indexOf(indexer.tracksPath)
                    val path = trimmed.substring(start)
                    pathLines.add(path)
                } else {
                    try {
                        val path = Path(trimmed)
                        if (path.exists() && path.isSymbolicLink()) {
                            val destination = path.resolveSymlinkAbsolute()
                            if (destination.startsWith(indexer.tracksPath)) {
                                pathLines.add(destination.absolutePathString())
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
            if (indexer.playlistsPath != null) {
                if (
                    trimmed.endsWith(indexer.playlistExtension) &&
                    trimmed.contains(indexer.playlistsPath)
                ) {
                    val start = when {
                        trimmed.contains(indexer.playlistsPath) -> trimmed.indexOf(indexer.playlistsPath)
                        indexer.albumsPath?.let { p -> trimmed.contains(p) }
                            ?: false -> trimmed.indexOf(indexer.albumsPath)

                        else -> -1
                    }

                    if (start >= 0) {
                        val pathString = trimmed.substring(start)
                        if (trimmed.contains(indexer.playlistsPath)) {
                            try {
                                pathLines.add(Path(pathString).absolutePathString())
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }
            logProxy(it)
        }

        val paths = pathLines.map { Path(it) }.filter { it.exists() }.toMutableList()

        val pathAlternation =
            "(${indexer.playlistsPath}|${indexer.albumsPath})"

        val playlistRegex =
            Regex("${pathAlternation}/([^/]+?)/_[^/]+?\\.m3u", RegexOption.DOT_MATCHES_ALL)

        playlistRegex.findAll(result.fullOutput.oneLine()).forEach { matchResult ->
            val fullMatch = matchResult.value

            try {
                val path = Path(fullMatch)
                if (path.exists()) paths.add(path)
                else {
                    val siblings = path.parent.listDirectoryEntries()
                    val path = siblings.find { it.extension == indexer.playlistExtension }
                    if (path?.exists() == true) paths.add(path)
                    else logger.info("PlaylistPath $path ($fullMatch) does not exist")
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        for (path in paths.filter { it.extension == indexer.playlistExtension }) {
            if (!path.exists()) continue
            for (line in path.readLines()) {
                try {
                    val songPath = path.resolveRelativeAbsolute(line)
                    if (songPath.exists()) paths.add(songPath)
                } catch (_: Throwable) {
                }
            }
        }

        val songPaths = paths
            .filter { it.extension == indexer.audioExtension }
            .distinctBy { it.absolutePathString() }
        val playlistPaths = paths
            .filter { it.isInside(indexer.playlistsPath ?: it.parent.absolutePathString()) }
            .filter { it.extension == indexer.playlistExtension }
            .distinctBy { it.absolutePathString() }

        if (result.exitCode == 0 && indexer.isActive.compareAndSet(expectedValue = false, newValue = true)) {
            if (songPaths.isEmpty()) indexer.start(logProxy)
            else indexer.start(songPaths, playlistPaths.ifEmpty { emptyList() }, stdout = logProxy)
            indexer.isActive.store(false)

            logProxy("Took ${currentTry + 1} tr${if (currentTry == 0) "y" else "ies"} to download.")
        } else if (result.exitCode == 1 && indexer.playlistsPath != null) {
            val pathAlternation =
                "(${indexer.playlistsPath}|${indexer.albumsPath})"

            val rootPath = Path(indexer.playlistsPath).parent.absolute()

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

                        delay(10000)

                        val (newResult, newPaths) = collectDownloadedFiles(
                            command,
                            maxRetries,
                            currentTry + 1,
                            aliveCheck,
                            logProxy
                        )

                        result = newResult
                        paths.clear()
                        paths.addAll(newPaths)
                    } else if (!brokenFilePath.isInside(rootPath)) {
                        logProxy("File ($brokenFilePath) not inside $rootPath")
                    }
                } catch (_: Throwable) {
                }
            } else logger.info(errorRegex.toString())
        }

        return Pair(result, paths)
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun downloadContent(
        url: String,
        maxRetries: Int = 5,
        aliveCheck: suspend () -> Boolean = { true },
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        val command = listOf("tdn", "dl", url)
        val (result) = collectDownloadedFiles(command, maxRetries, 0, aliveCheck, onLiveOutput)
        return result
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun downloadFavoriteCollection(
        type: TdnFavoriteType,
        maxRetries: Int = 5,
        aliveCheck: suspend () -> Boolean = { true },
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        val command = listOf("tdn", "dl_fav", type.name)
        val (result) = collectDownloadedFiles(command, maxRetries, 0, aliveCheck, onLiveOutput)
        return result
    }

    private suspend fun executeTdn(
        command: List<String>,
        aliveCheck: suspend () -> Boolean,
        onLineReceived: suspend (String) -> Unit
    ): ProcessExecutionResult {
        if (command.isEmpty() || command[0] != "tdn") {
            return ProcessExecutionResult(-1, "Error: Command must start with 'tdn'.", "")
        }

        logger.info("Starting command: ${command.joinToString(" ")}")

        val fullOutput = StringBuilder()

        val currentJob = currentCoroutineContext().job
        var completionHandle: DisposableHandle? = null

        var process: Process? = null

        return coroutineScope {
            val checkJob = launch {
                while (aliveCheck()) {
                    delay(200)
                    ensureActive()
                }

                cancel("Client disconnected", ClientCloseException())
            }

            try {
                process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                completionHandle = currentJob.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        process?.destroyForcibly()
                    }
                }

                val reader = InputStreamReader(process.inputStream)

                reader.lineFlow().collect { line ->
                    currentCoroutineContext().ensureActive()

                    fullOutput.appendLine(line)
                    onLineReceived(line)
                }

                val exitCode = process.waitFor()

                return@coroutineScope ProcessExecutionResult(exitCode, fullOutput.toString(), "")

            } catch (e: Exception) {
                if (e is ClientCloseException || e.cause is ClientCloseException) logger.info("Client disconnected.")
                else e.printStackTrace()
                return@coroutineScope ProcessExecutionResult(
                    -2,
                    fullOutput.toString(),
                    "Failed to execute 'tdn'. Error: ${e.message}"
                )
            } finally {
                completionHandle?.dispose()

                if (checkJob.isActive) checkJob.cancel()
                if (process?.isAlive == true) process.destroyForcibly()
            }
        }
    }
}