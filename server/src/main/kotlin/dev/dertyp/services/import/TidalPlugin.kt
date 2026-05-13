package dev.dertyp.services.import

import dev.dertyp.PlatformUUID
import dev.dertyp.core.sha256
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.User
import dev.dertyp.getDateFromISO
import dev.dertyp.plugins.*
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
    override val importBackends: List<ImportBackend> = listOf(ImportBackend.Tdn, ImportBackend.Tiddl)

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
    private val importerProxy: ImporterProxy by inject()
    private lateinit var indexer: TidalIndexer
    private lateinit var proxyImporter: TidalProxyImporter

    override fun init(context: PluginContext) {
        indexer = TidalIndexer(context)
        tiddlService.indexer = indexer
        tdnService.indexer = indexer
        proxyImporter = TidalProxyImporter(tiddlService, tdnService, importerProxy)
    }

    override fun getKoinModule(): Module = module {
        singleOf(::TiddlService)
        singleOf(::TdnService)
    }

    override fun getImporters(): List<IImporter> = listOf(tiddlService, tdnService, proxyImporter)
    override fun getIndexer(): IPluginIndexer = indexer
}

class TidalProxyImporter(
    private val tiddl: TiddlService,
    private val tdn: TdnService,
    private val importerProxy: ImporterProxy
) : IImporter {
    override val id: String = "tidal"
    override val name: String = "Tidal"
    override val pluginId: String = "tidal"
    override var indexer: IPluginIndexer
        get() = current().indexer
        set(value) {
            tiddl.indexer = value
            tdn.indexer = value
        }

    override val enabled: Boolean get() = tiddl.enabled || tdn.enabled
    override val metadataType get() = current().metadataType

    private fun current(): IImporter {
        val default = importerProxy.defaultService.id
        if (default == TiddlService.ID && tiddl.enabled) return tiddl
        if (default == TdnService.ID && tdn.enabled) return tdn

        return if (tiddl.enabled) tiddl else tdn
    }

    override fun canHandle(url: String): Boolean = current().canHandle(url)
    override suspend fun parseUrl(url: String) = current().parseUrl(url)
    override suspend fun getWrapper(type: Type, ids: List<String>, user: User) = current().getWrapper(type, ids, user)
    override suspend fun importIds(ids: List<String>, type: Type, user: User, callback: suspend (List<String>) -> Unit) = current().importIds(ids, type, user, callback)
    override suspend fun importContent(urls: List<String>, maxRetries: Int, aliveCheck: suspend () -> Boolean, userId: PlatformUUID?, onLiveOutput: suspend (String) -> Unit) = current().importContent(urls, maxRetries, aliveCheck, userId, onLiveOutput)
    override suspend fun importFavoriteCollection(type: ImportFavType, maxRetries: Int, aliveCheck: suspend () -> Boolean, userId: PlatformUUID?, onLiveOutput: suspend (String) -> Unit) = current().importFavoriteCollection(type, maxRetries, aliveCheck, userId, onLiveOutput)
    override suspend fun syncFavorites(user: User, onProgress: suspend (Double, String) -> Unit) = current().syncFavorites(user, onProgress)
    override suspend fun search(query: String, count: Int) = current().search(query, count)
    override suspend fun updateAlbumMetadata(albumId: PlatformUUID, originalId: String) = current().updateAlbumMetadata(albumId, originalId)
    override suspend fun login(aliveCheck: suspend () -> Boolean, onLiveOutput: suspend (String) -> Unit) = current().login(aliveCheck, onLiveOutput)
    override fun extractLoginUrl(log: String) = current().extractLoginUrl(log)
    override suspend fun authorized(aliveCheck: suspend () -> Boolean) = current().authorized(aliveCheck)
    override fun tokenFileExists() = current().tokenFileExists()
}
