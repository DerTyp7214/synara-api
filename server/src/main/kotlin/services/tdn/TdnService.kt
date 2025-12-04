package dev.dertyp.services

import dev.dertyp.Indexer
import dev.dertyp.core.*
import kotlinx.coroutines.*
import java.io.InputStreamReader
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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
        command: MutableList<String>,
        maxRetries: Int = 5,
        currentTry: Int = 0,
        aliveCheck: suspend () -> Boolean,
        logProxy: suspend (String) -> Unit
    ): Pair<ProcessExecutionResult, List<Path>> {
        val startTime = Instant.now().toEpochMilli()

        val pathLines = mutableListOf<String>()
        var result = executeTdn(command, aliveCheck) {
            if (!aliveCheck()) throw ClientCloseException()
            logProxy(it)
        }

        if (indexer.tracksPath != null) {
            val paths = Path(indexer.tracksPath).getModifiedSince(startTime)

            for (path in paths) {
                pathLines.add(path.absolutePathString())
            }
        }

        if (indexer.playlistsPath != null) {
            val paths = Path(indexer.playlistsPath).getModifiedSince(startTime)

            for (path in paths) {
                pathLines.add(path.absolutePathString())
            }
        }

        logProxy("Found ${pathLines.size} files since the download started.")

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
            else indexer.start(songPaths, playlistPaths, stdout = logProxy)
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

                        (0 until 10).forEach { i ->
                            logProxy("Waiting for 500ms (${i + 1}/10)")
                            if (!aliveCheck()) throw ClientCloseException()
                            delay(500)
                        }

                        val (newResult, newPaths) = collectDownloadedFiles(
                            command,
                            maxRetries,
                            currentTry + 1,
                            aliveCheck,
                            logProxy
                        )

                        result = newResult
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
        val command = mutableListOf("tdn", "dl", url)
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
        val command = mutableListOf("tdn", "dl_fav", type.name)
        val (result) = collectDownloadedFiles(command, maxRetries, 0, aliveCheck, onLiveOutput)
        return result
    }

    suspend fun login(
        aliveCheck: suspend () -> Boolean = { true },
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        val command = mutableListOf("tdn", "login")
        return executeTdn(command, aliveCheck, onLiveOutput)
    }

    private suspend fun executeTdn(
        command: MutableList<String>,
        aliveCheck: suspend () -> Boolean,
        onLineReceived: suspend (String) -> Unit
    ): ProcessExecutionResult {
        if (command.isEmpty() || (command[0] != "tdn" && command[0] != "python3")) {
            return ProcessExecutionResult(-1, "Error: Command must start with 'tdn'.", "")
        }

        if (command[0] != "python3") {
            command[0] = "tidal_dl_ng.cli"
            command.add(0, "python3")
            command.add(1, "-u")
            command.add(2, "-m")
        }

        val timeString = LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME).split(".").first()
        logger.info("[$timeString] Starting command: ${command.joinToString(" ")}")

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
                    .apply { environment()["COLUMNS"] = "500" }
                    .start()

                completionHandle = currentJob.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        process?.destroyForcibly()
                    }
                }

                val outputJob = launch {
                    val reader = InputStreamReader(process.inputStream)

                    try {
                        reader.lineFlow().collect { line ->
                            currentCoroutineContext().ensureActive()

                            fullOutput.appendLine(line)
                            if (line.isNotBlank()) onLineReceived(line)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }

                val exitCode = process.waitFor()
                outputJob.join()

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