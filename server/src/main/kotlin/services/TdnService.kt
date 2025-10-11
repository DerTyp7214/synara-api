package dev.dertyp.services

import dev.dertyp.Indexer
import dev.dertyp.core.lineFlow
import dev.dertyp.core.resolveRelativeAbsolute
import dev.dertyp.core.resolveSymlinkAbsolute
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.io.InputStreamReader
import java.nio.file.Path
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

class TdnService(private val indexer: Indexer) : Service() {
    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun collectDownloadedFiles(
        command: List<String>,
        logProxy: suspend (String) -> Unit
    ): Pair<ProcessExecutionResult, List<Path>> {
        val pathLines = mutableListOf<String>()
        val result = executeTdn(command) {
            val trimmed = it.trim().removeSurrounding("'")
            if (indexer.tracksPath != null) {
                if (
                    trimmed.startsWith(indexer.tracksPath) &&
                    trimmed.endsWith(indexer.audioExtension)
                ) {
                    pathLines.add(trimmed)
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
                    val start = trimmed.indexOf(indexer.playlistsPath)
                    val pathString = trimmed.substring(start)
                    pathLines.add(pathString)

                    try {
                        val path = Path(pathString)
                        if (path.exists()) {
                            for (line in path.readLines()) {
                                pathLines.add(path.resolveRelativeAbsolute(line).absolutePathString())
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
            logProxy(it)
        }

        val paths = pathLines.map { Path(it) }.filter { it.exists() }.distinctBy { it.absolutePathString() }
        val songPaths = paths.filter { it.extension == indexer.audioExtension }
        val playlistPaths = paths.filter { it.extension == indexer.playlistExtension }

        if (result.exitCode == 0 && indexer.isActive.compareAndSet(expectedValue = false, newValue = true)) {
            if (pathLines.isEmpty()) indexer.start(logProxy)
            else indexer.start(songPaths, playlistPaths.ifEmpty {
                indexer.playlistsPath?.let { listOf(Path(it)) } ?: emptyList()
            }, stdout = logProxy)
            indexer.isActive.store(false)
        }

        return Pair(result, paths)
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun downloadContent(url: String, onLiveOutput: suspend (String) -> Unit): ProcessExecutionResult {
        val command = listOf("tdn", "dl", url)
        val (result) = collectDownloadedFiles(command, onLiveOutput)
        return result
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun downloadFavoriteCollection(
        type: TdnFavoriteType,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        val command = listOf("tdn", "dl_fav", type.name)
        val (result) = collectDownloadedFiles(command, onLiveOutput)
        return result
    }

    private suspend fun executeTdn(
        command: List<String>,
        onLineReceived: suspend (String) -> Unit
    ): ProcessExecutionResult {
        if (command.isEmpty() || command[0] != "tdn") {
            return ProcessExecutionResult(-1, "Error: Command must start with 'tdn'.", "")
        }

        println("Starting command: ${command.joinToString(" ")}")

        val fullOutput = StringBuilder()

        val currentJob = currentCoroutineContext().job
        var completionHandle: DisposableHandle? = null

        var process: Process? = null

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

            return ProcessExecutionResult(exitCode, fullOutput.toString(), "")

        } catch (e: Exception) {
            e.printStackTrace()
            return ProcessExecutionResult(-2, fullOutput.toString(), "Failed to execute 'tdn'. Error: ${e.message}")
        } finally {
            completionHandle?.dispose()

            if (process?.isAlive == true) process.destroyForcibly()
        }
    }
}