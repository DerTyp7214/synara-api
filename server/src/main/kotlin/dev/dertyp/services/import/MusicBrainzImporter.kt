package dev.dertyp.services.import

import dev.dertyp.PlatformUUID
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.plugins.*
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MusicBrainzService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

class MusicBrainzImporter(private val context: PluginContext) : IImporter, KoinComponent {
    override val id: String = "musicbrainz"
    override val name: String = "MusicBrainz"
    override val pluginId: String = "musicbrainz"
    override lateinit var indexer: IPluginIndexer
    
    private val mbService: MusicBrainzService by inject()
    private val pluginManager: PluginManager by inject()

    private val mbRecordingRegex = Regex("musicbrainz\\.org/recording/([a-f0-9-]+)", RegexOption.IGNORE_CASE)
    private val mbReleaseRegex = Regex("musicbrainz\\.org/release/([a-f0-9-]+)", RegexOption.IGNORE_CASE)
    private val mbReleaseGroupRegex = Regex("musicbrainz\\.org/release-group/([a-f0-9-]+)", RegexOption.IGNORE_CASE)
    
    private val lbRecordingRegex = Regex("listenbrainz\\.org/(?:player/)?recording/([a-f0-9-]+)", RegexOption.IGNORE_CASE)
    private val lbReleaseRegex = Regex("listenbrainz\\.org/(?:player/)?release/([a-f0-9-]+)", RegexOption.IGNORE_CASE)

    override fun canHandle(url: String): Boolean {
        return mbRecordingRegex.containsMatchIn(url) ||
                mbReleaseRegex.containsMatchIn(url) ||
                mbReleaseGroupRegex.containsMatchIn(url) ||
                lbRecordingRegex.containsMatchIn(url) ||
                lbReleaseRegex.containsMatchIn(url)
    }

    override suspend fun parseUrl(url: String): Pair<String, Type?>? {
        mbRecordingRegex.find(url)?.let { return it.groupValues[1] to Type.SONG }
        mbReleaseRegex.find(url)?.let { return it.groupValues[1] to Type.ALBUM }
        mbReleaseGroupRegex.find(url)?.let { return it.groupValues[1] to Type.MIX }
        lbRecordingRegex.find(url)?.let { return it.groupValues[1] to Type.SONG }
        lbReleaseRegex.find(url)?.let { return it.groupValues[1] to Type.ALBUM }
        return null
    }

    override suspend fun importContent(
        urls: List<String>,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID?,
        metadata: IMetadataService.BaseMetadata?,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        for (url in urls) {
            val (mbidStr, type) = parseUrl(url) ?: continue
            val mbid = try { UUID.fromString(mbidStr) } catch (_: Exception) { continue }
            
            onLiveOutput("Fetching relations for MusicBrainz $type: $mbid...")
            
            val mbMetadata = when (type) {
                Type.SONG -> context.metadataService.getTrackByMbId(IMetadataService.MetadataType.musicBrainz, mbid)
                Type.ALBUM -> context.metadataService.getAlbumByMbId(IMetadataService.MetadataType.musicBrainz, mbid)
                Type.MIX -> context.metadataService.getAlbumByMbId(IMetadataService.MetadataType.musicBrainz, mbid)
                else -> null
            }

            val relations = when (type) {
                Type.SONG -> mbService.fetchRecordingById(mbid)?.relations
                Type.ALBUM -> mbService.fetchReleaseById(mbid)?.relations
                Type.MIX -> mbService.fetchReleaseGroupById(mbid)?.relations
                else -> null
            }
            
            val externalUrls = relations?.mapNotNull { it.url?.resource } ?: emptyList()
            
            val validUrls = externalUrls.filter { extUrl ->
                pluginManager.getAllImporters().any { it.id != this.id && it.enabled && it.canHandle(extUrl) }
            }
            
            if (validUrls.isNotEmpty()) {
                onLiveOutput("Found ${validUrls.size} supported streaming links. Queueing for import...")
                context.importService.addToQueue(UrlImportQueueEntry(
                    urls = validUrls.toMutableList(), 
                    byUser = userId,
                    metadata = mbMetadata
                ))
            } else {
                onLiveOutput("No supported streaming links found for $url")
            }
        }
        return ProcessExecutionResult(0, "MusicBrainz import process finished", "")
    }

    override suspend fun getWrapper(type: Type, ids: List<String>, user: User) = IdsWrapper.from(type, ids.associateBy { UUID.randomUUID().mostSignificantBits })
    override suspend fun importIds(ids: List<String>, type: Type, user: User, callback: suspend (List<String>) -> Unit) = Pair(false, emptyList<UserSong>())
    override suspend fun importFavoriteCollection(type: ImportFavType, maxRetries: Int, aliveCheck: suspend () -> Boolean, userId: PlatformUUID?, onLiveOutput: suspend (String) -> Unit) = ProcessExecutionResult.EMPTY
    override suspend fun syncFavorites(user: User, onProgress: suspend (Double, String) -> Unit) {}
    override suspend fun search(query: String, count: Int) = emptyList<SearchResult>()
    override suspend fun login(aliveCheck: suspend () -> Boolean, onLiveOutput: suspend (String) -> Unit) = ProcessExecutionResult.EMPTY
    override suspend fun authorized(aliveCheck: suspend () -> Boolean): Boolean = true
    override fun tokenFileExists(): Boolean = true
}
