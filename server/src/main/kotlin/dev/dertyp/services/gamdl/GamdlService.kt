package dev.dertyp.services.gamdl

import dev.dertyp.PlatformUUID
import dev.dertyp.audio.AudioConfig
import dev.dertyp.audio.LosslessFormat
import dev.dertyp.core.*
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.services.SongService
import dev.dertyp.services.import.*
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.utils.parsers.ParserFactory
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.*

@OptIn(ExperimentalAtomicApi::class)
class GamdlService(
    indexer: IPluginIndexer,
    storageService: IServerStorageService
) : BaseImporter(indexer, storageService) {
    override val id: String = ID
    override val metadataType = IMetadataService.MetadataType.appleMusic
    override val enabled: Boolean get() = gamdlPath != null && cookiesFile().exists()

    private val environment by inject<ApplicationEnvironment>()
    private val songService by inject<SongService>()
    private val audioConfig by inject<AudioConfig>()
    private val importService by inject<ImportService>()

    private val gamdlPath = findInPath("gamdl")
    private val ffmpegPath = findInPath("ffmpeg")

    companion object {
        val ID = ImportBackend.Gamdl.id
        private const val STOREFRONT = "us"
    }

    override val capabilities: Set<ImporterCapability> = setOf(
        ImporterCapability.IMPORT_SONG,
        ImporterCapability.IMPORT_ALBUM,
        ImporterCapability.IMPORT_ARTIST,
        ImporterCapability.IMPORT_PLAYLIST,
        ImporterCapability.CREDENTIALS,
    )

    private val cookiesPath: String
        get() = environment.config.propertyOrNull("gamdl.cookiesPath")?.getString()?.ifBlank { null } ?: "cookies.txt"
    private val wvdPath: String?
        get() = environment.config.propertyOrNull("gamdl.wvdPath")?.getString()?.ifBlank { null }
    private val codec: String?
        get() = environment.config.propertyOrNull("gamdl.codecSong")?.getString()?.ifBlank { null }

    private fun cookiesFile() = File(cookiesPath)

    override val loginCommand: MutableList<String> = mutableListOf()
    override val favImportCommand: MutableList<String> = mutableListOf()

    override val importCommand: MutableList<String>
        get() = buildList {
            add("gamdl")
            add("--no-config-file")
            add("--cookies-path"); add(cookiesPath)
            add("--output-path"); add(workingDirectory!!.absolutePath)

            add("--template-folder-album"); add("{album_id}")
            add("--template-folder-compilation"); add("{album_id}")
            add("--template-file-single-disc"); add("{title_id}")
            add("--template-file-multi-disc"); add("{title_id}")
            wvdPath?.let { add("--wvd-path"); add(it) }
            codec?.let { add("--codec-song"); add(it) }
        }.toMutableList()

    override fun authorizedCheck(result: ProcessExecutionResult): Boolean = cookiesFile().exists()

    override fun tokenFileExists(): Boolean = cookiesFile().exists()

    override fun canHandle(url: String): Boolean =
        ParserFactory.getParserForProvider("apple")?.canHandle(url) ?: false

    override suspend fun parseUrl(url: String): Pair<String, Type?>? =
        ParserFactory.getParserForProvider("apple")?.parse(url)

    override suspend fun provideCredentials(credentials: ImporterCredentials) {
        if (credentials !is GamdlCredentials) return
        cookiesFile().apply { parentFile?.mkdirs() }.writeText(credentials.cookiesTxt)
        credentials.wvdBase64?.let { b64 ->
            wvdPath?.let { path ->
                File(path).apply { parentFile?.mkdirs() }.writeBytes(Base64.getDecoder().decode(b64))
            }
        }
        logger.info("Received gamdl credentials; importer enabled=$enabled")
    }

    override suspend fun importFavoriteCollection(
        type: ImportFavType,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID?,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult =
        ProcessExecutionResult(-1, "gamdl does not support favorite imports.", "")

    private fun songUrl(id: String) = "https://music.apple.com/$STOREFRONT/song/$id"

    override suspend fun getWrapper(type: Type, ids: List<String>, user: User): IdsWrapper {
        val meta = MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, environment)
        return when (type) {
            Type.MIX, Type.SONG, Type.VIDEO ->
                IdsWrapper.from(type, ids.associateBy { UUID.randomUUID().mostSignificantBits })

            Type.ARTIST -> {
                val groups = ids.asFlow().map { id ->
                    IdsGroup(
                        id,
                        emptyFlow(),
                        IMetadataService.FlowArtist(
                            id = id,
                            tracks = meta.getArtistTracks(id, priority = HttpClientPriority.HIGH)
                        )
                    )
                }
                IdsWrapper(type, groups)
            }

            Type.ALBUM -> {
                val groups = ids.asFlow().map { id ->
                    IdsGroup(
                        id,
                        emptyFlow(),
                        IMetadataService.Album(
                            id = id,
                            title = "",
                            tracks = meta.getAlbumTracks(id, priority = HttpClientPriority.HIGH)
                        )
                    )
                }
                IdsWrapper(type, groups)
            }

            Type.PLAYLIST -> {
                val groups = meta.getPlaylistsByIds(ids, true, user, priority = HttpClientPriority.HIGH).map { playlist ->
                    IdsGroup(
                        playlist.id,
                        playlist.sharedTracks.map { UUID.randomUUID().mostSignificantBits to it.id },
                        playlist
                    )
                }
                IdsWrapper(type, groups)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun importIds(
        ids: List<String>,
        type: Type,
        user: User,
        callback: suspend (List<String>) -> Unit
    ): Pair<Boolean, List<UserSong>> {
        var contentToImport = false

        when (type) {
            Type.SONG, Type.MIX -> {
                val urls = ids.map { songUrl(it) }
                val existingSongs = songService.byOriginalIds(urls, user.id)
                val existingUrls = existingSongs.map { it.originalUrl }
                val toImport = urls.filter { it !in existingUrls }
                if (toImport.isNotEmpty()) {
                    contentToImport = true
                    importService.addToQueue(
                        UrlImportQueueEntry(
                            urls = toImport.toMutableList(),
                            ids = ids,
                            byUser = user.id,
                            type = type,
                            importer = ImportBackend(id)
                        ) { callback(ids) }
                    )
                }
                return contentToImport to existingSongs
            }

            Type.ALBUM, Type.ARTIST, Type.PLAYLIST -> {
                // getWrapper carries the expanded tracks in each group's metadata flow
                // (ids is empty for these types), so read from there — mirrors TidalBaseImporter.
                getWrapper(type, ids, user).idGroups.buffer(2).collect { idGroup ->
                    val tracks = when (val meta = idGroup.metadata) {
                        is IMetadataService.Album -> meta.tracks
                        is IMetadataService.FlowArtist -> meta.sharedTracks
                        is IMetadataService.FlowPlaylist -> meta.sharedTracks
                        else -> emptyFlow()
                    }
                    tracks.buffer(100)
                        .filterExisting(songService = songService, user = user, deduplicateByIsrc = false)
                        .collect { trackChunk ->
                            contentToImport = true
                            val trackIds = trackChunk.map { it.id }
                            importService.addToQueue(
                                UrlImportQueueEntry(
                                    urls = trackIds.map { songUrl(it) }.toMutableList(),
                                    ids = trackIds,
                                    byUser = user.id,
                                    maxRetries = trackChunk.size,
                                    type = Type.SONG,
                                    importer = ImportBackend(id)
                                ) { callback(trackIds) }
                            )
                        }
                }
            }

            else -> {}
        }

        return contentToImport to emptyList()
    }

    override suspend fun executeImporter(
        command: Collection<String>,
        aliveCheck: suspend () -> Boolean,
        directory: File?,
        onLineReceived: suspend (String) -> Unit
    ): ProcessExecutionResult {
        val cmd = command.toMutableList()
        if (cmd.isEmpty() || (cmd[0] != "gamdl" && cmd[0] != gamdlPath)) {
            return ProcessExecutionResult(-1, "Error: Command must start with 'gamdl'.", "")
        }
        if (gamdlPath == null) {
            return ProcessExecutionResult(-1, "Error: The gamdl path does not exist.", "")
        }
        cmd[0] = gamdlPath

        return executeCommand(cmd, aliveCheck, logger, directory, onLineReceived = onLineReceived)
    }

    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun importContent(
        urls: List<String>,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID?,
        metadata: IMetadataService.BaseMetadata?,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        loggingIn.waitForChange(false)

        val startTime = Instant.now().toEpochMilli()
        onLiveOutput("Starting gamdl import for ${urls.size} url(s)...")

        val result = executeImporter(importCommand + urls, aliveCheck, workingDirectory) {
            if (!aliveCheck()) throw ClientCloseException()
            onLiveOutput(it)
        }

        val tracksPath = pluginStorage.tracksPath
        val losslessPaths = mutableListOf<Path>()
        if (tracksPath != null) {
            val newFiles = Path(tracksPath).getModifiedSince(startTime)
            val m4aFiles = newFiles.filter { it.extension == "m4a" && it.exists() }
            val target = audioConfig.losslessFormat
            onLiveOutput("gamdl downloaded ${m4aFiles.size} file(s); transcoding to ${target.extension}...")
            for (m4a in m4aFiles) {
                transcodeToLossless(m4a, target, aliveCheck, onLiveOutput)?.let { losslessPaths.add(it) }
            }
        }

        onLiveOutput("Queueing ${losslessPaths.size} song(s) for indexing...")
        indexer.queue(losslessPaths.distinct(), emptyList(), indexer.id, userId, onLiveOutput).await()

        return result
    }

    internal fun losslessFfmpegArgs(target: LosslessFormat): List<String> = when (target) {
        LosslessFormat.FLAC -> listOf("-map", "0:a", "-map", "0:v?", "-c:a", "flac", "-c:v", "copy")
        LosslessFormat.WAV -> listOf("-map", "0:a", "-vn", "-c:a", "pcm_s16le", "-f", "wav")
        LosslessFormat.AIFF -> listOf("-map", "0:a", "-vn", "-c:a", "pcm_s16be", "-f", "aiff")
    }

    private suspend fun transcodeToLossless(
        m4a: Path,
        target: LosslessFormat,
        aliveCheck: suspend () -> Boolean,
        onLiveOutput: suspend (String) -> Unit
    ): Path? {
        if (ffmpegPath == null) {
            onLiveOutput("ffmpeg not found on PATH; cannot transcode ${m4a.absolutePathString()}")
            return null
        }
        val output = m4a.resolveSibling(m4a.nameWithoutExtension + "." + target.extension)
        val cmd = listOf(ffmpegPath, "-y", "-i", m4a.absolutePathString()) +
            losslessFfmpegArgs(target) +
            listOf("-map_metadata", "0", output.absolutePathString())
        val res = executeCommand(cmd, aliveCheck, logger, workingDirectory) { onLiveOutput(it) }
        return if (res.exitCode == 0 && output.exists()) {
            runCatching { m4a.deleteIfExists() }
            output
        } else {
            onLiveOutput("Transcode failed for ${m4a.absolutePathString()} (exit ${res.exitCode}); keeping source.")
            null
        }
    }
}
