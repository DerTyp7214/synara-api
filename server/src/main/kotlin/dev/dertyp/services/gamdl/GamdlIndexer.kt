package dev.dertyp.services.gamdl

import dev.dertyp.core.sha256
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.InsertableSong
import dev.dertyp.getDateFromISO
import dev.dertyp.plugins.*
import dev.dertyp.services.import.ImportBackend
import dev.dertyp.services.import.Gamdl
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
import kotlin.io.path.name

class GamdlIndexer(context: PluginContext) : BaseIndexer(context, IMetadataService.MetadataType.appleMusic) {
    override val id: String = "gamdl"
    override val name: String = "gamdl Indexer"
    override val importBackends: List<ImportBackend> = listOf(ImportBackend.Gamdl)

    override fun canHandle(path: Path): Boolean {
        if (!super.canHandle(path)) return false
        return importBackends.any {
            val tracksPath = context.storageService.forImporter(it).tracksPath ?: return@any false
            path.toAbsolutePath().toString().startsWith(File(tracksPath).absolutePath)
        }
    }

    override suspend fun groupByAlbum(files: List<Path>): Pair<Map<String, InsertableImage>, Map<InsertableAlbum, List<AudioFile>>> =
        coroutineScope {
            val semaphore = Semaphore(2)
            val map = ConcurrentHashMap<InsertableAlbum, MutableList<AudioFile>>()
            val images = ConcurrentHashMap<String, InsertableImage>()

            val audioFilesWithTags = files.filter { isAudio(it) }.map { file ->
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
                            Triple(file, audioFile, hash)
                        } catch (e: Exception) {
                            context.logger.error("Failed to read audio file: $file", e)
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()

            val isrcsToResolve = audioFilesWithTags
                .filter { it.second.album == null && it.second.isrc != null }
                .mapNotNull { it.second.isrc }.distinct()

            val resolvedIsrcTracks = if (isrcsToResolve.isNotEmpty()) {
                isrcsToResolve.mapNotNull { isrc ->
                    try {
                        context.metadataService.getTrackByIsrc(IMetadataService.MetadataType.musicBrainz, isrc)
                    } catch (_: Exception) {
                        null
                    }
                }.associateBy { it.isrc!! }
            } else emptyMap()

            val mbAlbumIds = resolvedIsrcTracks.values.mapNotNull { it.albumId }.distinct()
            val resolvedMbAlbums = if (mbAlbumIds.isNotEmpty()) {
                try {
                    context.metadataService.getAlbumsByIds(IMetadataService.MetadataType.musicBrainz, mbAlbumIds).associateBy { it.id }
                } catch (e: Exception) {
                    context.logger.warn("Failed to fetch MusicBrainz metadata for albums", e)
                    emptyMap()
                }
            } else emptyMap()

            audioFilesWithTags.forEach { (file, audioFile, hash) ->
                val mbTrack = audioFile.isrc?.let { resolvedIsrcTracks[it] }

                val name = mbTrack?.albumTitle ?: audioFile.album ?: audioFile.title ?: "Unknown Album"
                val delimiter = getArtistDelimiter(audioFile)
                val artists = audioFile.getAlbumArtists(delimiter).ifEmpty { audioFile.getArtists(delimiter) }.sorted()
                val songCount = audioFile.songCount ?: 0
                val year = audioFile.year
                val releaseDate = getDateFromISO(year) ?: mbTrack?.albumId?.let { resolvedMbAlbums[it]?.releaseDate }

                val rawBarcode = audioFile.barcode
                val barcode = if (rawBarcode?.uppercase() == "BARCODE") null else rawBarcode

                val mbReleaseId = audioFile.musicBrainzReleaseId?.let {
                    try { UUID.fromString(it) } catch (_: Exception) { null }
                }

                val originalId = pluginStorages.firstNotNullOfOrNull { it.tracksPath }?.let { _ ->
                    pluginStorages.find { storage ->
                        storage.tracksPath?.let { file.absolutePathString().startsWith(it) } == true
                    }?.let { _ ->
                        val folderName = file.parent.name
                        if (folderName.all { it.isDigit() }) "appleMusic:$folderName" else null
                    }
                }

                val album = InsertableAlbum(
                    name = name,
                    artists = artists,
                    releaseDate = releaseDate,
                    coverHash = hash,
                    songCount = songCount,
                    originalId = originalId,
                    barcode = barcode,
                    musicBrainzId = mbReleaseId,
                )

                val albumList = map.computeIfAbsent(album) { Collections.synchronizedList(mutableListOf()) }
                albumList.add(audioFile)
            }

            val metadataAlbums = if (metadataType != null) {
                val albumsToUpdate = map.keys.filter {
                    it.originalId != null && (it.songCount == 0 || it.releaseDate == null)
                }.mapNotNull { it.originalId }.distinct()

                if (albumsToUpdate.isNotEmpty()) {
                    try {
                        context.metadataService.getAlbumsByIds(metadataType!!, albumsToUpdate).associateBy { it.id }
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
                        album.songCount,
                        album.musicBrainzId
                    )
                }
                .mapValues { (_, lists) ->
                    val mergedAlbum = lists.first().first.copy(
                        coverHash = lists.firstNotNullOfOrNull { it.first.coverHash },
                        originalId = lists.firstNotNullOfOrNull { it.first.originalId },
                        musicBrainzId = lists.firstNotNullOfOrNull { it.first.musicBrainzId },
                    )
                    mergedAlbum to lists.flatMap { it.second }
                }
                .values
                .associate { it }

            Pair(images.toMap(), albums)
        }

    public override suspend fun insertableSongFromFile(audioFile: AudioFile, album: InsertableAlbum): InsertableSong {
        val base = super.insertableSongFromFile(audioFile, album)
        if (base.musicBrainzId != null) return base
        val isrc = base.isrc ?: return base
        return try {
            val recording = context.metadataService.getTrackByIsrc(IMetadataService.MetadataType.musicBrainz, isrc)
            val mbId = recording?.id?.let { try { UUID.fromString(it) } catch (_: Exception) { null } }
            if (mbId != null) base.copy(musicBrainzId = mbId) else base
        } catch (e: Exception) {
            context.logger.warn("Failed to resolve MusicBrainz by ISRC for ${audioFile.file.name}", e)
            base
        }
    }
}
