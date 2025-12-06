package dev.dertyp

import dev.dertyp.core.*
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.InsertableSong
import dev.dertyp.services.ImageService
import dev.dertyp.services.PlaylistService
import dev.dertyp.services.SongService
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.logging.Level
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

class Indexer(
    environment: ApplicationEnvironment,
    private val songService: SongService,
    private val imageService: ImageService,
    private val playlistService: PlaylistService,
) {
    val logger = KtorSimpleLogger("Indexer")

    val tracksPath = environment.config.propertyOrNull("audio.tracks")?.getString()?.removeSuffix("/")
    val albumsPath = environment.config.propertyOrNull("audio.albums")?.getString()?.removeSuffix("/")
    val playlistsPath = environment.config.propertyOrNull("audio.playlists")?.getString()?.removeSuffix("/")

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

    suspend fun start(stdout: suspend (String) -> Unit) = coroutineScope {
        val log = { line: String -> async { stdout(line) } }
        if (tracksPath == null || playlistsPath == null)
            return@coroutineScope log("audio paths are not configured").await()

        val songRootPath = Path(tracksPath)
        val playlistRootPath = Path(playlistsPath)

        start(
            listOf(songRootPath, *secondaryTracksPaths.toTypedArray()),
            listOf(playlistRootPath), stdout
        )
    }

    @OptIn(ExperimentalTime::class)
    suspend fun start(
        songPaths: List<Path>,
        playlistPaths: List<Path> = emptyList(),
        stdout: suspend (String) -> Unit
    ) = coroutineScope {
        AudioFileIO.logger.level = Level.WARNING

        val log = { line: String -> async { stdout(line) } }

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

        val (images, albums) = groupByAlbum(songs)

        log("Saving covers to database.").await()

        val imageResult = imageService.createBatch(images.values.toList())

        log("Saved ${imageResult.size} of ${images.size} images.").await()

        log("Grouping songs by albums.").await()

        val songResult = songService.createBatch(albums.entries.map { (album, songs) ->
            songs.map { audioFile -> insertableSongFromFile(audioFile, album) }
        }.flatten())

        val totalSize = songResult.values.fold(0L) { acc, song -> acc + song.fileSize }

        log("Saved ${songResult.size} of ${albums.values.flatten().size} songs. (${totalSize.toHumanReadableSize()})").await()

        log("Found ${images.size} unique images.").await()
        log("Found ${albums.size} unique albums.").await()

        log("Start playlist parsing.").await()

        val playlistCount = parsePlaylists(playlists)

        log("Parsed and inserted $playlistCount playlists.")
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

    private fun groupByAlbum(files: List<Path>): Pair<Map<String, InsertableImage>, Map<InsertableAlbum, List<AudioFile>>> {
        val map = mutableMapOf<InsertableAlbum, MutableList<AudioFile>>()
        val images = mutableMapOf<String, InsertableImage>()

        for (file in files.filter { it.extension == audioExtension }) {
            try {
                val audioFile = AudioFileIO.read(file.toFile())

                val cover = audioFile.coverImage
                val hash = cover?.sha256()
                if (hash != null && !images.containsKey(hash)) images.put(
                    hash, InsertableImage(
                        data = cover,
                        imageHash = hash,
                        origin = file.absolutePathString()
                    )
                )

                val name = audioFile.album ?: audioFile.title
                val artists = audioFile.albumArtists.ifEmpty { audioFile.artists }
                val songCount = audioFile.songCount ?: 0
                val year = audioFile.year

                if (name == null) continue

                val releaseDate = try {
                    LocalDate.parse(year!!, DateTimeFormatter.ISO_LOCAL_DATE)
                } catch (_: Exception) {
                    null
                }

                val album = InsertableAlbum(
                    name = name,
                    artists = artists,
                    releaseDate = releaseDate,
                    coverHash = hash,
                    songCount = songCount
                )

                if (!map.hasAlbum(album)) map[album] = mutableListOf()

                map[map.getAlbum(album)]?.add(audioFile)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return Pair(images, map)
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

        val url = tag.getFirst("URL") ?: ""
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
            explicit = audioFile.file.nameWithoutExtension.endsWith("(Explicit)"),
            releaseDate = releaseDate,
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

fun Route.Indexer(service: SongService, imageService: ImageService, playlistService: PlaylistService) =
    Indexer(environment, service, imageService, playlistService)

fun Application.Indexer(service: SongService, imageService: ImageService, playlistService: PlaylistService) =
    Indexer(environment, service, imageService, playlistService)