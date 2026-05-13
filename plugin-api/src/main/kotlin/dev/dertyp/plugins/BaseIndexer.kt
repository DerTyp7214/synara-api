package dev.dertyp.plugins

import dev.dertyp.PlatformUUID
import dev.dertyp.core.sha256
import dev.dertyp.data.*
import dev.dertyp.services.metadata.IMetadataService
import kotlinx.coroutines.*
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
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@OptIn(ExperimentalAtomicApi::class)
abstract class BaseIndexer(
    val context: PluginContext,
    val metadataType: IMetadataService.MetadataType? = null
) : IPluginIndexer {

    override val audioExtension = "flac"
    override val playlistExtension = "m3u"

    protected val pluginStorages by lazy { importBackends.map { context.storageService.forImporter(it) } }

    val isActive = AtomicBoolean(false)

    protected val queueMutex = Mutex()
    protected val indexQueue = mutableListOf<IndexQueueItem>()

    protected data class IndexQueueItem(
        val songPaths: List<Path>,
        val playlistPaths: List<Path>,
        val stdout: suspend (String) -> Unit,
        val userId: PlatformUUID? = null
    )

    override fun canHandle(path: Path): Boolean {
        return path.extension == audioExtension || path.extension == playlistExtension
    }

    protected open fun buildMap(paths: List<Path>): List<Path> {
        val files = mutableListOf<Path>()
        paths.forEach {
            if (it.isDirectory()) {
                it.toFile().mkdirs()
                files.addAll(buildMap(it.listDirectoryEntries()))
            } else if (!it.isSymbolicLink() && canHandle(it)) {
                files.add(it)
            }
        }
        return files
    }

    protected open suspend fun parsePlaylists(files: List<Path>, userId: PlatformUUID? = null): Int {
        val playlists = files.filter { it.extension == playlistExtension }.map { file ->
            val name = file.fileName.nameWithoutExtension.removePrefix("_")
            val songs = file.readText().lines().map { file.parent.resolve(it).normalize().toString() }
            InsertablePlaylist(name = name, songPaths = songs)
        }
        return context.playlistLibrary.createBatch(playlists, userId).size
    }

    protected open fun updateAlbumMetadata(
        album: InsertableAlbum,
        metadata: IMetadataService.Album?,
        songs: List<AudioFile>
    ): InsertableAlbum {
        var finalAlbum = album
        if (metadata != null && (finalAlbum.songCount == 0 || finalAlbum.releaseDate == null)) {
            finalAlbum = finalAlbum.copy(
                songCount = if (finalAlbum.songCount == 0) metadata.trackCount else finalAlbum.songCount,
                releaseDate = metadata.releaseDate ?: finalAlbum.releaseDate,
                artists = metadata.artists.ifEmpty { finalAlbum.artists }.sorted()
            )
        }

        if (finalAlbum.songCount == 0) {
            finalAlbum = finalAlbum.copy(songCount = songs.size)
        }
        return finalAlbum
    }

    protected open fun getArtistDelimiter(audioFile: AudioFile): String = artistDelimiter

    open suspend fun groupByAlbum(files: List<Path>): Pair<Map<String, InsertableImage>, Map<InsertableAlbum, List<AudioFile>>> =
        coroutineScope {
            val semaphore = Semaphore(2)
            val map = ConcurrentHashMap<InsertableAlbum, MutableList<AudioFile>>()
            val images = ConcurrentHashMap<String, InsertableImage>()

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
                            val delimiter = getArtistDelimiter(audioFile)
                            val artists = audioFile.getAlbumArtists(delimiter).ifEmpty { audioFile.getArtists(delimiter) }.sorted()
                            val songCount = audioFile.songCount ?: 0
                            val year = audioFile.year

                            if (name == null) return@withPermit

                            val releaseDate = try {
                                LocalDate.parse(year!!, DateTimeFormatter.ISO_LOCAL_DATE)
                            } catch (_: Exception) {
                                null
                            }

                            val originalId = pluginStorages.firstNotNullOfOrNull { it.tracksPath }?.let { _ ->
                                pluginStorages.find { storage ->
                                    storage.tracksPath?.let { file.absolutePathString().startsWith(it) } == true
                                }?.let { "$id:${file.parent.name}" }
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
                            context.logger.error("Failed to read audio file: $file", e)
                        }
                    }
                }
            }.awaitAll()

            val metadataAlbums = if (metadataType != null) {
                val albumsToUpdate = map.keys.filter {
                    it.originalId != null && (it.songCount == 0 || it.releaseDate == null)
                }.mapNotNull { it.originalId }.distinct()

                if (albumsToUpdate.isNotEmpty()) {
                    try {
                        context.metadataService.getAlbumsByIds(metadataType, albumsToUpdate).associateBy { it.id }
                    } catch (e: Exception) {
                        context.logger.warn("Failed to fetch album metadata for update", e)
                        emptyMap()
                    }
                } else emptyMap()
            } else emptyMap()

            val albums = map.entries.asSequence()
                .map { (album, list) ->
                    updateAlbumMetadata(album, metadataAlbums[album.originalId], list) to list
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

    override suspend fun queue(
        songPaths: List<Path>,
        playlistPaths: List<Path>,
        type: String?,
        userId: PlatformUUID?,
        stdout: suspend (String) -> Unit
    ): Deferred<Unit> {
        queueMutex.withLock {
            indexQueue.add(IndexQueueItem(songPaths, playlistPaths, stdout, userId))
        }

        return CoroutineScope(Dispatchers.IO + SupervisorJob()).async {
            startIndexer()
        }
    }

    private suspend fun startIndexer() {
        if (!isActive.compareAndSet(expectedValue = false, newValue = true)) return

        while (queueMutex.withLock { indexQueue.isNotEmpty() }) {
            val item = queueMutex.withLock { indexQueue.removeFirst() }
            start(item.songPaths, item.playlistPaths, item.userId, item.stdout)
        }

        isActive.store(false)
        if (queueMutex.withLock { indexQueue.isNotEmpty() }) startIndexer()
    }

    open suspend fun start(
        songPaths: List<Path>,
        playlistPaths: List<Path> = emptyList(),
        userId: PlatformUUID? = null,
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
        context.imageLibrary.createBatch(images.values.toList())

        log("Grouping songs by albums.").await()
        val groupedSongs = albums.entries.flatMap { (album, songs) ->
            songs.map { audioFile -> async { insertableSongFromFile(audioFile, album) } }.awaitAll()
        }

        log("Insert songs.").await()
        val insertStart = Clock.System.now()
        val songResult = context.songLibrary.createBatch(groupedSongs)
        val insertDuration = Clock.System.now().minus(insertStart).toString(DurationUnit.SECONDS)
        log("Inserting songs took $insertDuration seconds.").await()

        val totalSize = songResult.values.fold(0L) { acc, song -> acc + song.fileSize }
        log("Saved ${songResult.size} of ${albums.values.flatten().size} songs. (${totalSize.toHumanReadableSize()})").await()

        log("Found ${images.size} unique images.").await()
        log("Found ${albums.size} unique albums.").await()

        log("Start playlist parsing.").await()
        val playlistCount = parsePlaylists(playlists, userId)
        log("Parsed and inserted $playlistCount playlists.").await()

        afterIndex(songResult, log)
    }

    protected open suspend fun afterIndex(songResult: Map<PlatformUUID, Song>, log: (String) -> Deferred<Unit>) {
        if (songResult.isNotEmpty()) {
            context.scheduleService.schedulePostIndexTasks()
        }
    }

    protected open suspend fun insertableSongFromFile(
        audioFile: AudioFile,
        album: InsertableAlbum
    ): InsertableSong {
        val tag = audioFile.tag
        val header = audioFile.audioHeader

        val rawTitle = audioFile.title ?: ""
        val titleCleaned = rawTitle.replace("\uD83C\uDD74", "").trim()
        val isExplicitByEmoji = rawTitle.contains("\uD83C\uDD74")

        val delimiter = getArtistDelimiter(audioFile)
        val artists = audioFile.getArtists(delimiter)
        val copyright = tag.getAll(FieldKey.COPYRIGHT)
        val trackNumber = tag.getFirst(FieldKey.TRACK).toIntOrNull() ?: 1
        val discNumber = tag.getFirst(FieldKey.DISC_NO).toIntOrNull() ?: 1

        val url = tag.getFirst("URL").ifEmpty { "" }
        val cover = audioFile.coverImage
        val lyrics = tag.getFirst(FieldKey.LYRICS) ?: ""
        val year = tag.getFirst(FieldKey.YEAR)
        val musicBrainzId = tag.getFirst(FieldKey.MUSICBRAINZ_TRACK_ID).ifBlank { null }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }

        val duration = header.preciseTrackLength.seconds.inWholeMilliseconds
        val sampleRate = header.sampleRateAsNumber
        val bitsPerSample = header.bitsPerSample
        val bitRate = header.bitRateAsNumber
        val bpm = tag.getFirst(FieldKey.BPM).toDoubleOrNull()

        val releaseDate = try {
            LocalDate.parse(year, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            null
        }

        val isExplicit = audioFile.file.nameWithoutExtension.endsWith("(Explicit)") || isExplicitByEmoji || audioFile.isExplicit

        var needsCommit = false
        if (isExplicit && !audioFile.isExplicit) {
            audioFile.setExplicit(true)
            needsCommit = true
        }
        if (isExplicitByEmoji && rawTitle != titleCleaned) {
            tag.setField(FieldKey.TITLE, titleCleaned)
            needsCommit = true
        }

        if (needsCommit) {
            try {
                audioFile.commit()
            } catch (e: Exception) {
                context.logger.error("Failed to update tags for ${audioFile.file.absolutePath}: ${e.message}")
            }
        }

        return InsertableSong(
            title = titleCleaned,
            artists = artists,
            album = album,
            duration = duration,
            explicit = isExplicit,
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
            coverHash = cover?.sha256(),
            musicBrainzId = musicBrainzId,
            audioData = SongAudioData(bpm = bpm)
        )
    }
}
