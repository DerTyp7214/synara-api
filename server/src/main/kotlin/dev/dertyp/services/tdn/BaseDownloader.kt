package dev.dertyp.services.tdn

import dev.dertyp.Indexer
import dev.dertyp.core.*
import dev.dertyp.services.Service
import dev.dertyp.services.StorageService
import kotlinx.coroutines.yield
import java.nio.file.Path
import java.time.Instant
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalAtomicApi::class)
abstract class BaseDownloader(internal val indexer: Indexer, internal val storageService: StorageService) : Service() {
    internal val loggingIn = AtomicBoolean(false)

    internal abstract fun authorizedCheck(result: ProcessExecutionResult): Boolean
    internal open fun parseFavType(favType: TidalFavType): String = favType.name
    abstract val loginCommand: MutableList<String>
    abstract val downloadCommand: MutableList<String>
    abstract val favDownloadCommand: MutableList<String>

    internal open suspend fun handleErrors(
        command: Collection<String>,
        result: ProcessExecutionResult,
        currentTry: Int,
        maxRetries: Int,
        paths: MutableList<Path>,
        aliveCheck: suspend () -> Boolean,
        logProxy: suspend (String) -> Unit
    ): ProcessExecutionResult = result

    @OptIn(ExperimentalAtomicApi::class)
    internal suspend fun collectDownloadedFiles(
        command: Collection<String>,
        maxRetries: Int = 5,
        currentTry: Int = 0,
        aliveCheck: suspend () -> Boolean,
        logProxy: suspend (String) -> Unit
    ): Pair<ProcessExecutionResult, List<Path>> {
        val startTime = Instant.now().toEpochMilli()
        val pathLines = mutableListOf<String>()

        val paths = mutableListOf<Path>()
        val songPaths = mutableListOf<Path>()
        val playlistPaths = mutableListOf<Path>()

        var result: ProcessExecutionResult

        try {
            result = executeDownloader(command, aliveCheck) {
                if (!aliveCheck()) throw ClientCloseException()
                logProxy(it)
            }

            if (storageService.tracksPath != null) {
                val paths = Path(storageService.tracksPath).getModifiedSince(startTime)

                for (path in paths) {
                    pathLines.add(path.absolutePathString())
                }
            }

            if (storageService.playlistsPath != null) {
                val paths = Path(storageService.playlistsPath).getModifiedSince(startTime)

                for (path in paths) {
                    pathLines.add(path.absolutePathString())
                }
            }

            logProxy("Found ${pathLines.size} files since the download started.")

            paths.addAll(pathLines.map { Path(it) }.filter { it.exists() }.toMutableList())

            logProxy("Found ${paths.size} valid paths.")

            val pathAlternation =
                "(${storageService.playlistsPath}|${storageService.albumsPath})"

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

            if (result.exitCode == 1 && storageService.playlistsPath != null) {
                result = handleErrors(command, result, currentTry, maxRetries, paths, aliveCheck, logProxy)
            }
        } finally {
            if (currentTry == 0) {
                songPaths.addAll(
                    paths
                        .filter { it.extension == indexer.audioExtension }
                        .distinctBy { it.absolutePathString() }
                )

                playlistPaths.addAll(
                    paths
                        .filter { it.isInside(storageService.playlistsPath ?: it.parent.absolutePathString()) }
                        .filter { it.extension == indexer.playlistExtension }
                        .distinctBy { it.absolutePathString() }
                )

                indexer.queue(songPaths.distinct(), playlistPaths.distinct(), stdout = logProxy).await()
            }
        }

        return Pair(result, paths)
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun downloadContent(
        url: String,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        loggingIn.waitForChange(false)

        val command = downloadCommand + url
        val (result) = collectDownloadedFiles(command, maxRetries, 0, aliveCheck, onLiveOutput)
        return result
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun downloadContent(
        urls: List<String>,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        loggingIn.waitForChange(false)

        val command = downloadCommand + urls
        val (result) = collectDownloadedFiles(command, maxRetries, 0, aliveCheck, onLiveOutput)
        return result
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun downloadFavoriteCollection(
        type: TidalFavType,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        loggingIn.waitForChange(false)

        val command = favDownloadCommand + parseFavType(favType = type)
        val (result) = collectDownloadedFiles(command, maxRetries, 0, aliveCheck, onLiveOutput)
        return result
    }

    @OptIn(ExperimentalTime::class)
    suspend fun login(
        aliveCheck: suspend () -> Boolean,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        loggingIn.waitForChange(false)
        loggingIn.store(true)

        val command = loginCommand
        val startTime = Clock.System.now()
        val response = try {
            executeDownloader(command, {
                Clock.System.now().minus(startTime) < 3.minutes && aliveCheck()
            }, {
                onLiveOutput(it)
                yield()
            })
        } finally {
            loggingIn.store(false)
        }

        return response
    }

    @ExperimentalTime
    suspend fun authorized(
        aliveCheck: suspend () -> Boolean = { true },
    ): Boolean {
        loggingIn.waitForChange(false)
        loggingIn.store(true)

        val command = loginCommand
        val startTime = Clock.System.now()
        val result = try {
            executeDownloader(command, { Clock.System.now().minus(startTime) < 10.seconds && aliveCheck() }) {
                yield()
            }
        } finally {
            loggingIn.store(false)
        }


        return authorizedCheck(result)
    }

    internal abstract suspend fun executeDownloader(
        command: Collection<String>,
        aliveCheck: suspend () -> Boolean,
        onLineReceived: suspend (String) -> Unit = {}
    ): ProcessExecutionResult
}