package dev.dertyp.plugins

import dev.dertyp.PlatformUUID
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.services.download.DownloadFavType
import dev.dertyp.services.download.IdsWrapper
import dev.dertyp.services.download.ProcessExecutionResult
import dev.dertyp.services.download.Type
import dev.dertyp.services.metadata.IMetadataService

data class SearchResult(
    val id: String,
    val title: String,
    val artists: List<String>,
    val coverUrl: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

interface IDownloader {
    val id: String
    val name: String
    val pluginId: String
    var indexer: IPluginIndexer
    val enabled: Boolean get() = true
    val metadataType: IMetadataService.MetadataType? get() = null

    fun canHandle(url: String): Boolean
    suspend fun parseUrl(url: String): Pair<String, Type>? = null

    suspend fun getWrapper(
        type: Type,
        ids: List<String>,
        user: User
    ): IdsWrapper

    suspend fun downloadIds(
        ids: List<String>,
        type: Type,
        user: User,
        callback: suspend (List<String>) -> Unit
    ): Pair<Boolean, List<UserSong>>

    suspend fun downloadContent(
        urls: List<String>,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID? = null,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult

    suspend fun downloadFavoriteCollection(
        type: DownloadFavType,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID? = null,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult

    suspend fun syncFavorites(
        user: User,
        onProgress: suspend (Double, String) -> Unit
    )

    suspend fun search(
        query: String,
        count: Int
    ): List<SearchResult>

    suspend fun updateAlbumMetadata(
        albumId: PlatformUUID,
        originalId: String
    ): Boolean = false

    suspend fun login(
        aliveCheck: suspend () -> Boolean,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult

    fun extractLoginUrl(log: String): String? = null

    suspend fun authorized(
        aliveCheck: suspend () -> Boolean
    ): Boolean

    fun tokenFileExists(): Boolean
}
