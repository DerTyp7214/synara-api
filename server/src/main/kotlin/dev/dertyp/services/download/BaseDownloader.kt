package dev.dertyp.services.download

import dev.dertyp.PlatformUUID
import dev.dertyp.core.*
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.plugins.IDownloader
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.plugins.SearchResult
import dev.dertyp.services.Service
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalAtomicApi::class)
abstract class BaseDownloader(override var indexer: IPluginIndexer, internal val storageService: IServerStorageService) : Service(), IDownloader {
    override val id: String get() = this::class.simpleName!!.lowercase().removeSuffix("service")
    override val name: String get() = this::class.simpleName!!.removeSuffix("service")
    override val pluginId: String get() = id

    protected val pluginStorage by lazy { storageService.forDownloader(DownloadBackend(id)) }

    open val workingDirectory: File? get() = pluginStorage.tracksPath?.let { File(it).apply { mkdirs() } }

    internal val loggingIn = AtomicBoolean(false)

    internal abstract fun authorizedCheck(result: ProcessExecutionResult): Boolean
    internal open fun parseFavType(favType: DownloadFavType): String = favType.name
    abstract override fun canHandle(url: String): Boolean
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
        userId: PlatformUUID? = null,
        logProxy: suspend (String) -> Unit
    ): ProcessExecutionResult = result

    @OptIn(ExperimentalAtomicApi::class)
    internal open suspend fun collectDownloadedFiles(
        command: Collection<String>,
        maxRetries: Int = 5,
        currentTry: Int = 0,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID? = null,
        logProxy: suspend (String) -> Unit,
        onFilesFound: suspend (List<Path>) -> Unit = {}
    ): Pair<ProcessExecutionResult, List<Path>> {
        val startTime = Instant.now().toEpochMilli()
        val pathLines = mutableListOf<String>()

        val paths = mutableListOf<Path>()
        val songPaths = mutableListOf<Path>()
        val playlistPaths = mutableListOf<Path>()

        var result: ProcessExecutionResult

        try {
            logProxy("Starting download process...")
            result = executeDownloader(command, aliveCheck, workingDirectory) {
                if (!aliveCheck()) throw ClientCloseException()
                logProxy(it)
            }

            if (pluginStorage.tracksPath != null) {
                val foundPaths = Path(pluginStorage.tracksPath!!).getModifiedSince(startTime)

                for (path in foundPaths) {
                    pathLines.add(path.absolutePathString())
                }
            }

            if (pluginStorage.playlistsPath != null) {
                val foundPaths = Path(pluginStorage.playlistsPath!!).getModifiedSince(startTime)

                for (path in foundPaths) {
                    pathLines.add(path.absolutePathString())
                }
            }

            logProxy("Found ${pathLines.size} files since the download started.")

            paths.addAll(pathLines.map { Path(it) }.filter { it.exists() }.toMutableList())

            logProxy("Found ${paths.size} valid paths.")
            logProxy("Download process finished with exit code ${result.exitCode}.")

            val pathAlternation =
                "(${pluginStorage.playlistsPath}|${pluginStorage.albumsPath})"

            val playlistRegex =
                Regex("${pathAlternation}/([^/]+?)/_[^/]+?\\.m3u", RegexOption.DOT_MATCHES_ALL)

            playlistRegex.findAll(result.fullOutput.oneLine()).forEach { matchResult ->
                val fullMatch = matchResult.value

                try {
                    val path = Path(fullMatch)
                    if (path.exists()) paths.add(path)
                    else {
                        val siblings = path.parent.listDirectoryEntries()
                        val siblingPath = siblings.find { it.extension == indexer.playlistExtension }
                        if (siblingPath?.exists() == true) paths.add(siblingPath)
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

            if (result.exitCode == 1 && pluginStorage.playlistsPath != null) {
                result = handleErrors(command, result, currentTry, maxRetries, paths, aliveCheck, userId, logProxy)
            }
            
            onFilesFound(paths)
        } finally {
            if (currentTry == 0) {
                songPaths.addAll(
                    paths
                        .filter { it.extension == indexer.audioExtension }
                        .distinctBy { it.absolutePathString() }
                )

                playlistPaths.addAll(
                    paths
                        .filter { it.isInside(pluginStorage.playlistsPath ?: it.parent.absolutePathString()) }
                        .filter { it.extension == indexer.playlistExtension }
                        .distinctBy { it.absolutePathString() }
                )

                logProxy("Queueing ${songPaths.size} songs and ${playlistPaths.size} playlists for indexing...")
                indexer.queue(songPaths.distinct(), playlistPaths.distinct(), indexer.id, userId, logProxy).await()
            }
        }

        return Pair(result, paths)
    }

    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun getWrapper(
        type: Type,
        ids: List<String>,
        user: User
    ): IdsWrapper {
        return when (type) {
            Type.MIX -> IdsWrapper.from(type, ids.associateBy { UUID.randomUUID().mostSignificantBits })
            Type.SONG -> IdsWrapper.from(type, ids.associateBy { UUID.randomUUID().mostSignificantBits })
            else -> IdsWrapper.from(type, emptyMap())
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun downloadIds(
        ids: List<String>,
        type: Type,
        user: User,
        callback: suspend (List<String>) -> Unit
    ): Pair<Boolean, List<UserSong>> {
        return Pair(false, emptyList())
    }

    override suspend fun syncFavorites(
        user: User,
        onProgress: suspend (Double, String) -> Unit
    ) {
    }

    override suspend fun search(
        query: String,
        count: Int
    ): List<SearchResult> {
        return emptyList()
    }

    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun downloadContent(
        urls: List<String>,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID?,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        loggingIn.waitForChange(false)

        val command = downloadCommand + urls
        val (result) = collectDownloadedFiles(command, maxRetries, 0, aliveCheck, userId, onLiveOutput)
        return result
    }

    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun downloadFavoriteCollection(
        type: DownloadFavType,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID?,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        loggingIn.waitForChange(false)

        val command = favDownloadCommand + parseFavType(favType = type)
        val (result) = collectDownloadedFiles(command, maxRetries, 0, aliveCheck, userId, onLiveOutput)
        return result
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun login(
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
            }, workingDirectory) {
                onLiveOutput(it)
                yield()
            }
        } finally {
            loggingIn.store(false)
        }

        return response
    }

    @ExperimentalTime
    override suspend fun authorized(
        aliveCheck: suspend () -> Boolean,
    ): Boolean {
        loggingIn.waitForChange(false)
        loggingIn.store(true)

        val command = loginCommand
        val startTime = Clock.System.now()
        val result = try {
            executeDownloader(command, { Clock.System.now().minus(startTime) < 10.seconds && aliveCheck() }, workingDirectory) {
                yield()
            }
        } finally {
            loggingIn.store(false)
        }


        return authorizedCheck(result)
    }

    override fun tokenFileExists(): Boolean {
        return true
    }

    internal abstract suspend fun executeDownloader(
        command: Collection<String>,
        aliveCheck: suspend () -> Boolean,
        directory: File? = workingDirectory,
        onLineReceived: suspend (String) -> Unit = {}
    ): ProcessExecutionResult
}
