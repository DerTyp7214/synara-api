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
    private val logger = KtorSimpleLogger("Indexer")

    private val tracksPath = environment.config.propertyOrNull("audio.tracks")?.getString()
    private val albumsPath = environment.config.propertyOrNull("audio.albums")?.getString()
    private val playlistsPath = environment.config.propertyOrNull("audio.playlists")?.getString()

    private val audioExtension = "flac"
    private val playlistExtension = "m3u"

    @OptIn(ExperimentalTime::class)
    suspend fun start(stdout: suspend (String) -> Unit) = coroutineScope {
        AudioFileIO.logger.level = Level.WARNING

        val log = { line: String -> async { stdout(line) } }
        if (tracksPath == null || albumsPath == null || playlistsPath == null)
            return@coroutineScope log("audio paths are not configured")

        log("Starting Indexer").await()

        val songRootPath = Path(tracksPath)
        val playlistRootPath = Path(playlistsPath)

        log("Scanning ${songRootPath.toAbsolutePath()}").await()
        val startTime = Clock.System.now()

        val songs = buildMap(songRootPath)
        val playlists = buildMap(playlistRootPath)

        log(
            "Finished scanning directories. (${
                Clock.System.now().minus(startTime).inWholeMilliseconds / 1000f
            }s)"
        ).await()

        var successful = 0

        val (images, albums) = groupByAlbum(songs)

        log("Saving covers to database.").await()

        for ((_, image) in images) {
            if (imageService.getOrCreate(image) != null) log("Saved ${image.imageHash}.").await()
        }

        log("Grouping songs by albums.").await()

        for ((album, songs) in albums) {
            log("""Grouped ${songs.size} songs to "${album.name}" by ${album.artists.joinToString(", ")}.""")

            for (song in songs) {
                val insertableSong = insertableSongFromFile(song, album)

                val song = songService.getOrCreate(insertableSong)

                if (song == null) log("Song creation failed for ${insertableSong.title}")
                else successful++
            }
        }

        log("Found ${images.size} unique images.").await()
        log("Found ${albums.size} unique albums.").await()
        log("Found ${songs.size} songs, inserted $successful to the database.").await()

        log("Start playlist parsing.").await()

        val playlistCount = parsePlaylists(playlists)

        log("Parsed and inserted $playlistCount playlists.")
    }

    private fun buildMap(path: Path): List<Path> {
        val files = mutableListOf<Path>()

        path.listDirectoryEntries().forEach {
            if (it.isDirectory()) files.addAll(buildMap(it))
            else if ((it.extension == audioExtension || it.extension == playlistExtension) && !it.isSymbolicLink()) files.add(
                it
            )
        }

        return files
    }

    private suspend fun parsePlaylists(files: List<Path>): Int {
        var successful = 0
        for (file in files.filter { it.extension == playlistExtension }) {
            val name = file.fileName.nameWithoutExtension.removePrefix("_")
            val songs = file.readText().lines().map { file.parent.resolve(it).normalize().toString() }

            if (playlistService.getOrCreate(InsertablePlaylist(name, songs)) != null) successful++
        }

        return successful
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
                        imageHash = hash
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
            coverHash = cover?.sha256()
        )
    }
}

fun Route.Indexer(service: SongService, imageService: ImageService, playlistService: PlaylistService) =
    Indexer(environment, service, imageService, playlistService)

fun Application.Indexer(service: SongService, imageService: ImageService, playlistService: PlaylistService) =
    Indexer(environment, service, imageService, playlistService)