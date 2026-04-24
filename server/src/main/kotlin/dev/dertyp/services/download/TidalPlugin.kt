package dev.dertyp.services.download

import dev.dertyp.core.sha256
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableImage
import dev.dertyp.getDateFromISO
import dev.dertyp.plugins.BaseIndexer
import dev.dertyp.plugins.IDownloader
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.ISynaraPlugin
import dev.dertyp.plugins.PluginContext
import dev.dertyp.plugins.album
import dev.dertyp.plugins.coverImage
import dev.dertyp.plugins.getAlbumArtists
import dev.dertyp.plugins.getArtists
import dev.dertyp.plugins.musicBrainzTrackId
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
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import java.io.File
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.name

class TidalIndexer(context: PluginContext) : BaseIndexer(context, IMetadataService.MetadataType.tidal) {
    override val id: String = "tidal"
    override val name: String = "Tidal Indexer"
    override val downloadBackends: List<DownloadBackend> = listOf(DownloadBackend.Tdn, DownloadBackend.Tiddl)

    override fun canHandle(path: Path): Boolean {
        if (!super.canHandle(path)) return false
        return downloadBackends.any {
            val tracksPath = context.storageService.forDownloader(it).tracksPath ?: return@any false
            path.toAbsolutePath().toString().startsWith(File(tracksPath).absolutePath)
        }
    }

    override suspend fun groupByAlbum(files: List<Path>): Pair<Map<String, InsertableImage>, Map<InsertableAlbum, List<AudioFile>>> =
        coroutineScope {
            val semaphore = Semaphore(2)
            val map = ConcurrentHashMap<InsertableAlbum, MutableList<AudioFile>>()
            val images = ConcurrentHashMap<String, InsertableImage>()

            val audioFilesWithTags = files.filter { it.extension == audioExtension }.map { file ->
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

            val tracksToResolve = audioFilesWithTags.filter { it.second.album == null && it.second.musicBrainzTrackId != null }
            val mbTrackIds = tracksToResolve.mapNotNull { it.second.musicBrainzTrackId }.distinct()

            val resolvedMbTracks = if (mbTrackIds.isNotEmpty()) {
                try {
                    context.metadataService.getTracksByIds(IMetadataService.MetadataType.musicBrainz, mbTrackIds).associateBy { it.id }
                } catch (e: Exception) {
                    context.logger.warn("Failed to fetch MusicBrainz metadata for tracks", e)
                    emptyMap()
                }
            } else emptyMap()

            val mbAlbumIds = resolvedMbTracks.values.mapNotNull { it.albumId }.distinct()
            val resolvedMbAlbums = if (mbAlbumIds.isNotEmpty()) {
                try {
                    context.metadataService.getAlbumsByIds(IMetadataService.MetadataType.musicBrainz, mbAlbumIds).associateBy { it.id }
                } catch (e: Exception) {
                    context.logger.warn("Failed to fetch MusicBrainz metadata for albums", e)
                    emptyMap()
                }
            } else emptyMap()

            audioFilesWithTags.forEach { (file, audioFile, hash) ->
                val mbTrack = audioFile.musicBrainzTrackId?.let { resolvedMbTracks[it] }

                val name = mbTrack?.albumTitle ?: audioFile.album ?: audioFile.title
                val artists = audioFile.getAlbumArtists(artistDelimiter).ifEmpty { audioFile.getArtists(artistDelimiter) }.sorted()
                val songCount = audioFile.songCount ?: 0
                val year = audioFile.year

                if (name == null) return@forEach

                val releaseDate = getDateFromISO(year) ?: mbTrack?.albumId?.let { resolvedMbAlbums[it]?.releaseDate }

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
}

class TidalPlugin : ISynaraPlugin, KoinComponent {
    override val id: String = "tidal"
    override val name: String = "Tidal"
    override val enabled: Boolean get() = tiddlService.enabled || tdnService.enabled

    private val tiddlService: TiddlService by inject()
    private val tdnService: TdnService by inject()
    private lateinit var indexer: TidalIndexer

    override fun init(context: PluginContext) {
        indexer = TidalIndexer(context)
        tiddlService.indexer = indexer
        tdnService.indexer = indexer
    }

    override fun getKoinModule(): Module = module {
        singleOf(::TiddlService)
        singleOf(::TdnService)
    }

    override fun getDownloaders(): List<IDownloader> = listOf(tiddlService, tdnService)
    override fun getIndexer(): IPluginIndexer = indexer
}
