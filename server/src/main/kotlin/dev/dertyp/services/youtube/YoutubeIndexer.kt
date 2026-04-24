package dev.dertyp.services.youtube

import dev.dertyp.core.sha256
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.InsertableSong
import dev.dertyp.getDateFromISO
import dev.dertyp.plugins.BaseIndexer
import dev.dertyp.plugins.PluginContext
import dev.dertyp.plugins.album
import dev.dertyp.plugins.coverImage
import dev.dertyp.plugins.getAlbumArtists
import dev.dertyp.plugins.getArtists
import dev.dertyp.plugins.musicBrainzArtistId
import dev.dertyp.plugins.musicBrainzReleaseId
import dev.dertyp.plugins.songCount
import dev.dertyp.plugins.title
import dev.dertyp.plugins.year
import dev.dertyp.services.metadata.IMetadataService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension

class YoutubeIndexer(context: PluginContext) : BaseIndexer(context, IMetadataService.MetadataType.musicBrainz) {
    override val id: String = "youtube"
    override val name: String = "YouTube Indexer"

    override fun canHandle(path: Path): Boolean {
        if (!super.canHandle(path)) return false
        val tracksPath = context.storageService.tracksPath ?: return true
        return path.toAbsolutePath().toString().startsWith(File(tracksPath).absolutePath)
    }

    override suspend fun groupByAlbum(files: List<Path>): Pair<Map<String, InsertableImage>, Map<InsertableAlbum, List<AudioFile>>> =
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

                            val mbReleaseId = audioFile.musicBrainzReleaseId
                            val name = audioFile.album ?: audioFile.title ?: ""
                            val artists = audioFile.getAlbumArtists(artistDelimiter).ifEmpty { audioFile.getArtists(artistDelimiter) }.sorted()
                            val songCount = audioFile.songCount ?: 0
                            val year = audioFile.year

                            if (name.isBlank()) return@withPermit

                            val releaseDate = getDateFromISO(year)

                            val url = audioFile.tag.getFirst("URL")
                            val youtubeId = try {
                                val uri = java.net.URI(url)
                                val query = uri.query ?: ""
                                val params = query.split("&").associate {
                                    val parts = it.split("=")
                                    parts[0] to parts.getOrNull(1)
                                }
                                params["list"] ?: params["v"]
                            } catch (_: Exception) {
                                null
                            }

                            var finalAlbumName = name
                            var finalAlbumArtists = artists
                            var finalAlbumReleaseDate = releaseDate

                            val fallbackId = file.parent.fileName.toString()
                            val isMbId = try { UUID.fromString(fallbackId); true } catch (_: Exception) { false }
                            
                            var finalOriginalId = "$id:${youtubeId ?: if (!isMbId) fallbackId else audioFile.file.nameWithoutExtension}"

                            if (mbReleaseId != null) {
                                val existingAlbum = try { context.albumLibrary.byMusicBrainzId(UUID.fromString(mbReleaseId)) } catch (_: Exception) { null }
                                if (existingAlbum != null) {
                                    finalAlbumName = existingAlbum.name
                                    finalAlbumArtists = existingAlbum.artists.map { it.name }.sorted()
                                    finalAlbumReleaseDate = existingAlbum.releaseDate
                                    finalOriginalId = existingAlbum.originalId ?: finalOriginalId
                                }
                            }

                            val album = InsertableAlbum(
                                name = finalAlbumName,
                                artists = finalAlbumArtists,
                                releaseDate = finalAlbumReleaseDate,
                                coverHash = hash,
                                songCount = songCount,
                                originalId = finalOriginalId,
                            )

                            val albumList = map.computeIfAbsent(album) { Collections.synchronizedList(mutableListOf()) }
                            albumList.add(audioFile)
                        } catch (e: Exception) {
                            context.logger.error("Failed to read audio file: $file", e)
                        }
                    }
                }
            }.awaitAll()

            val albums = map.mapValues { it.value.toList() }
            Pair(images.toMap(), albums)
        }

    override suspend fun insertableSongFromFile(audioFile: AudioFile, album: InsertableAlbum): InsertableSong {
        val baseSong = super.insertableSongFromFile(audioFile, album)
        val mbId = baseSong.musicBrainzId ?: return baseSong

        return try {
            val recording = context.metadataService.getTrackByMbId(IMetadataService.MetadataType.musicBrainz, mbId)
            if (recording != null) {
                val artists = if (audioFile.musicBrainzArtistId != null) {
                    val ids = audioFile.musicBrainzArtistId!!.split("/").mapNotNull { try { UUID.fromString(it) } catch (_: Exception) { null } }
                    val matchedArtists = ids.mapNotNull { context.artistLibrary.byMusicBrainzId(it) }
                    if (matchedArtists.size == ids.size) {
                        matchedArtists.map { it.name }
                    } else recording.artists
                } else recording.artists

                baseSong.copy(
                    title = recording.title,
                    artists = artists.ifEmpty { baseSong.artists }
                )
            } else baseSong
        } catch (e: Exception) {
            context.logger.error("Failed to fetch full metadata for YouTube indexing from MusicBrainz: $mbId", e)
            baseSong
        }
    }
}
