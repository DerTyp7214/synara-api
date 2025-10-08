package dev.dertyp

import dev.dertyp.core.*
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.InsertableSong
import dev.dertyp.services.SongService
import io.ktor.server.application.*
import io.ktor.server.routing.*
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

class Indexer(environment: ApplicationEnvironment, private val service: SongService) {

    private val tracksPath = environment.config.propertyOrNull("audio.tracks")?.getString()
    private val albumsPath = environment.config.propertyOrNull("audio.albums")?.getString()
    private val playlistsPath = environment.config.propertyOrNull("audio.playlists")?.getString()

    @OptIn(ExperimentalTime::class)
    suspend fun start(stdout: suspend (String) -> Unit) = coroutineScope {
        AudioFileIO.logger.level = Level.WARNING

        val log = { line: String -> async { stdout(line) } }
        if (tracksPath == null) return@coroutineScope log("audio.tracks not set in the configuration file")

        log("Starting Indexer").await()

        val rootPath = Path(tracksPath)

        log("Scanning ${rootPath.toAbsolutePath()}").await()
        val startTime = Clock.System.now()

        val files = buildMap(rootPath)

        log(
            "Finished scanning directories. (${
                Clock.System.now().minus(startTime).inWholeMilliseconds / 1000f
            }s)"
        ).await()

        log("Grouping songs by albums.").await()

        val (images, albums) = groupByAlbum(files)

        for ((album, songs) in albums) {
            log("""Grouped ${songs.size} songs to "${album.name}" by ${album.artists.joinToString(", ")}.""")

            for (song in songs) {
                val insertableSong = insertableSongFromFile(song, album)

                val song = service.getOrCreate(insertableSong)

                if (song == null) log("Song creation failed for ${insertableSong.title} $insertableSong")
                else log(song.toString())
            }
        }

        log("Found ${images.size} unique images.").await()
        log("Found ${albums.size} unique albums.").await()
        log("Found ${files.size} songs.").await()
    }

    private fun buildMap(path: Path): List<Path> {
        val files = mutableListOf<Path>()

        path.listDirectoryEntries().forEach {
            if (it.isDirectory()) files.addAll(buildMap(it))
            else if (it.extension == "flac" && !it.isSymbolicLink()) files.add(it)
        }

        return files
    }

    private fun groupByAlbum(files: List<Path>): Pair<Map<String, InsertableImage>, Map<InsertableAlbum, List<AudioFile>>> {
        val map = mutableMapOf<InsertableAlbum, MutableList<AudioFile>>()
        val images = mutableMapOf<String, InsertableImage>()

        for (file in files) {
            try {
                val audioFile = AudioFileIO.read(file.toFile())

                val cover = audioFile.coverImage
                if (cover != null && !images.containsKey(cover.sha256())) images.put(
                    cover.sha256(), InsertableImage(
                        data = cover,
                        imageHash = cover.sha256()
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
                    coverHash = cover?.sha256(),
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
        val cover = if (audioFile.hasCover) audioFile.coverImage else null
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

fun Route.Indexer(service: SongService) = Indexer(environment, service)
fun Application.Indexer(service: SongService) = Indexer(environment, service)