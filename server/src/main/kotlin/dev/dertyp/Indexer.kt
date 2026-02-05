package dev.dertyp

import dev.dertyp.core.*
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.InsertableSong
import dev.dertyp.services.ImageService
import dev.dertyp.services.PlaylistService
import dev.dertyp.services.SongService
import dev.dertyp.services.StorageService
import dev.dertyp.services.metadata.MetadataService
import io.ktor.server.application.*
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime

class RpcIndexer(private val indexer: Indexer) : IIndexer {
    @OptIn(ExperimentalAtomicApi::class)
    override fun start(): Flow<String> = flow {
        if (!indexer.isActive.compareAndSet(expectedValue = false, newValue = true)) {
            indexer.logger.warn("Indexer is already running.")
            emit("Indexer is already running.")
            return@flow
        }

        indexer.logger.info("Starting indexing...")
        emit("Starting indexing...")
        indexer.start { stdout ->
            emit(stdout)
        }

        indexer.logger.info("Finished indexing...")
        emit("Finished indexing...")
        indexer.isActive.store(false)
    }
}

@Suppress("unused")
class Indexer(
    private val environment: ApplicationEnvironment,
    private val songService: SongService,
    private val imageService: ImageService,
    private val storageService: StorageService,
    private val playlistService: PlaylistService,
) {
    val logger = KtorSimpleLogger("Indexer")
    val secondaryTracksPaths = try {
        environment.config.propertyOrNull("audio.secondary-tracks")?.getList()?.map {
            Path(it.removeSuffix("/"))
        } ?: emptyList()
    } catch (_: Throwable) {
        logger.warn("Invalid 'audio.secondary-tracks'")
        emptyList()
    }

    val audioExtension = "flac"
    val playlistExtension = "m3u"

    @OptIn(ExperimentalAtomicApi::class)
    val isActive = AtomicBoolean(false)

    suspend fun queue(stdout: suspend (String) -> Unit) = coroutineScope {
        val log = { line: String -> async { stdout(line) } }
        if (storageService.tracksPath == null || storageService.playlistsPath == null)
            return@coroutineScope log("audio paths are not configured")

        val songRootPath = Path(storageService.tracksPath)
        val playlistRootPath = Path(storageService.playlistsPath)

        return@coroutineScope queue(
            listOf(songRootPath, *secondaryTracksPaths.toTypedArray()),
            listOf(playlistRootPath), stdout
        )
    }

    suspend fun start(stdout: suspend (String) -> Unit) = coroutineScope {
        val log = { line: String -> async { stdout(line) } }
        if (storageService.tracksPath == null || storageService.playlistsPath == null)
            return@coroutineScope log("audio paths are not configured").await()

        val songRootPath = Path(storageService.tracksPath)
        val playlistRootPath = Path(storageService.playlistsPath)

        start(
            listOf(songRootPath, *secondaryTracksPaths.toTypedArray()),
            listOf(playlistRootPath), stdout
        )
    }

    private val queueMutex = Mutex()
    private val indexQueue = mutableListOf<IndexQueueItem>()

    private data class IndexQueueItem(
        val songPaths: List<Path>,
        val playlistPaths: List<Path>,
        val stdout: suspend (String) -> Unit
    )

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun queue(
        songPaths: List<Path>,
        playlistPaths: List<Path>,
        stdout: suspend (String) -> Unit,
    ): Deferred<Unit> {
        queueMutex.withLock {
            indexQueue.add(IndexQueueItem(songPaths, playlistPaths, stdout))
            indexQueue.isNotEmpty()
        }

        return CoroutineScope(Dispatchers.IO + SupervisorJob()).async {
            startIndexer()
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun startIndexer() {
        if (!isActive.compareAndSet(expectedValue = false, newValue = true)) return

        while (queueMutex.withLock { indexQueue.isNotEmpty() }) {
            val item = queueMutex.withLock { indexQueue.removeFirst() }

            start(item.songPaths, item.playlistPaths, item.stdout)
        }

        isActive.store(false)

        if (queueMutex.withLock { indexQueue.isNotEmpty() }) startIndexer()
    }

    @OptIn(ExperimentalTime::class)
    suspend fun start(
        songPaths: List<Path>,
        playlistPaths: List<Path> = emptyList(),
        stdout: suspend (String) -> Unit
    ) = coroutineScope {
        AudioFileIO.logger.level = Level.WARNING

        val log = { line: String ->
            async {
                try {
                    stdout(line)
                } catch (_: Throwable) {
                }
            }
        }

        log("Starting Indexer").await()

        log("Scanning Songs ${songPaths.joinToString(", ") { it.absolutePathString() }}").await()
        log("Scanning Playlists ${playlistPaths.joinToString(", ") { it.absolutePathString() }}").await()
        val startTime = Clock.System.now()

        val songs = buildMap(songPaths)
        val playlists = buildMap(playlistPaths)

        log(
            "Finished scanning directories. (${
                Clock.System.now().minus(startTime).inWholeMilliseconds / 1000f
            }s)"
        ).await()

        log("Read songs from disc.").await()
        val readStart = Clock.System.now()

        val (images, albums) = groupByAlbum(songs)

        val readDuration = Clock.System.now().minus(readStart).toString(DurationUnit.SECONDS)
        log("Reading songs from disc took $readDuration seconds.").await()

        log("Saving covers to database.").await()

        val imageResult = imageService.createBatch(images.values.toList())

        log("Saved ${imageResult.size} of ${images.size} images.").await()

        log("Grouping songs by albums.").await()

        val groupedSongs = albums.entries.flatMap { (album, songs) ->
            songs.map { audioFile -> insertableSongFromFile(audioFile, album) }
        }

        log("Insert songs.").await()
        val insertStart = Clock.System.now()

        val songResult = songService.createBatch(groupedSongs)

        val insertDuration = Clock.System.now().minus(insertStart).toString(DurationUnit.SECONDS)

        log("Inserting songs took $insertDuration seconds.").await()

        val totalSize = songResult.values.fold(0L) { acc, song -> acc + song.fileSize }

        log("Saved ${songResult.size} of ${albums.values.flatten().size} songs. (${totalSize.toHumanReadableSize()})").await()

        log("Found ${images.size} unique images.").await()
        log("Found ${albums.size} unique albums.").await()

        log("Start playlist parsing.").await()

        val playlistCount = parsePlaylists(playlists)

        log("Parsed and inserted $playlistCount playlists.").await()
    }

    private fun buildMap(paths: List<Path>): List<Path> {
        val files = mutableListOf<Path>()

        paths.forEach {
            if (it.isDirectory()) it.toFile().mkdirs()
            if (it.isDirectory()) files.addAll(buildMap(it.listDirectoryEntries()))
            else if ((it.extension == audioExtension || it.extension == playlistExtension) && !it.isSymbolicLink()) files.add(
                it
            )
        }

        return files
    }

    private suspend fun parsePlaylists(files: List<Path>): Int {
        return playlistService.createBatch(
            files
                .filter { it.extension == playlistExtension }
                .map { file ->
                    val name = file.fileName.nameWithoutExtension.removePrefix("_")
                    val songs = file.readText().lines().map { file.parent.resolve(it).normalize().toString() }

                    InsertablePlaylist(name = name, songPaths = songs)
                }
        ).size
    }

    private suspend fun groupByAlbum(files: List<Path>): Pair<Map<String, InsertableImage>, Map<InsertableAlbum, List<AudioFile>>> =
        coroutineScope {
            val semaphore = Semaphore(2)

            val map = ConcurrentHashMap<InsertableAlbum, MutableList<AudioFile>>()
            val images = ConcurrentHashMap<String, InsertableImage>()

            val tidalService = MetadataService.getMetadataService(
                MetadataService.Companion.MetadataType.tidal,
                environment
            )

            files.filter { it.extension == audioExtension }.map { file ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val audioFile = AudioFileIO.read(file.toFile())

                            val cover = audioFile.coverImage
                            val hash = cover?.sha256()
                            if (hash != null) images.computeIfAbsent(hash) {
                                InsertableImage(
                                    data = cover,
                                    imageHash = hash,
                                    origin = file.absolutePathString()
                                )
                            }

                            val name = audioFile.album ?: audioFile.title
                            val artists = audioFile.albumArtists.ifEmpty { audioFile.artists }.sorted()
                            val songCount = audioFile.songCount ?: 0
                            val year = audioFile.year

                            if (name == null) return@withPermit

                            val releaseDate = try {
                                LocalDate.parse(year!!, DateTimeFormatter.ISO_LOCAL_DATE)
                            } catch (_: Exception) {
                                null
                            }

                            val originalId = storageService.tracksPath?.let { tracksPath ->
                                if (file.isInside(tracksPath)) file.parent.name
                                else null
                            }

                            val album = InsertableAlbum(
                                name = name,
                                artists = artists,
                                releaseDate = releaseDate,
                                coverHash = hash,
                                songCount = songCount,
                                originalId = originalId,
                            )

                            val albumList = map.computeIfAbsent(album) { Collections.synchronizedList(mutableListOf()) }
                            albumList.add(audioFile)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }.awaitAll()

            val albumsToUpdate = map.keys.filter {
                it.originalId != null && (it.songCount == 0 || it.releaseDate == null)
            }.mapNotNull { it.originalId }.distinct()

            val tidalAlbums = if (albumsToUpdate.isNotEmpty()) {
                try {
                    tidalService.getAlbumsByIds(albumsToUpdate).associateBy { it.id }
                } catch (_: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            val albums = map.entries.asSequence()
                .map { (album, list) ->
                    var finalAlbum = album

                    if (finalAlbum.originalId != null && (finalAlbum.songCount == 0 || finalAlbum.releaseDate == null)) {
                        tidalAlbums[finalAlbum.originalId]?.let { tidalAlbum ->
                            finalAlbum = finalAlbum.copy(
                                songCount = if (finalAlbum.songCount == 0) tidalAlbum.trackCount else finalAlbum.songCount,
                                releaseDate = finalAlbum.releaseDate ?: tidalAlbum.releaseDate
                            )
                        }
                    }

                    if (finalAlbum.songCount == 0) {
                        finalAlbum = finalAlbum.copy(songCount = list.size)
                    }

                    finalAlbum to list
                }
                .groupBy { (album, _) ->
                    listOf(
                        album.name,
                        album.artists.sorted().joinToString(", "),
                        album.releaseDate,
                        album.songCount
                    )
                }
                .mapValues { (_, lists) ->
                    val mergedAlbum = lists.first().first.copy(
                        coverHash = lists.firstNotNullOfOrNull { it.first.coverHash },
                        originalId = lists.firstNotNullOfOrNull { it.first.originalId }
                    )
                    mergedAlbum to lists.flatMap { it.second }
                }
                .values
                .associate { it }

            Pair(images.toMap(), albums)
        }

    private fun insertableSongFromFile(
        audioFile: AudioFile,
        album: InsertableAlbum
    ): InsertableSong {
        val tag = audioFile.tag
        val header = audioFile.audioHeader

        val title = audioFile.title ?: ""
        val artists = audioFile.artists
        val copyright = tag.getAll(FieldKey.COPYRIGHT)
        val trackNumber = tag.getFirst(FieldKey.TRACK).toIntOrNull() ?: 1
        val discNumber = tag.getFirst(FieldKey.DISC_NO).toIntOrNull() ?: 1

        val url = tag.getFirst("URL").ifEmpty {
            "https://tidal.com/browse/track/${
                audioFile.file.nameWithoutExtension.split(Regex("[ _()-]")).first()
            }"
        }
        val cover = audioFile.coverImage
        val lyrics = tag.getFirst(FieldKey.LYRICS) ?: ""
        val year = tag.getFirst(FieldKey.YEAR)

        val duration = header.preciseTrackLength.seconds.inWholeMilliseconds
        val sampleRate = header.sampleRateAsNumber
        val bitsPerSample = header.bitsPerSample
        val bitRate = header.bitRateAsNumber

        val releaseDate = try {
            LocalDate.parse(year, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            null
        }

        return InsertableSong(
            title = title,
            artists = artists,
            album = album,
            duration = duration,
            explicit = audioFile.file.nameWithoutExtension.endsWith("(Explicit)") || title.endsWith("\uD83C\uDD74"),
            releaseDate = releaseDate ?: album.releaseDate,
            lyrics = lyrics,
            path = audioFile.file.absolutePath,
            originalUrl = url,
            trackNumber = trackNumber,
            discNumber = discNumber,
            copyright = copyright?.joinToString(", ") ?: "",
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            bitRate = bitRate,
            fileSize = audioFile.file.length(),
            coverHash = cover?.sha256()
        )
    }
}