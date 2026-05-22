package dev.dertyp.services.import

import dev.dertyp.PlatformUUID
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.PluginManager
import dev.dertyp.plugins.SearchResult
import dev.dertyp.services.Service
import dev.dertyp.services.metadata.IMetadataService
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.ExperimentalTime

class ImporterProxy(
    private val pluginManager: PluginManager
) : Service() {
    var defaultService: ImportBackend = ImportBackend.Tiddl

    internal fun getImporter(service: ImportBackend): IImporter {
        val requested = pluginManager.getImporter(service.id)
        if (requested != null && requested.enabled) return requested

        return pluginManager.getAllImporters().find { it.enabled }
            ?: DisabledImporter(service.id)
    }

    private class DisabledImporter(override val id: String) : IImporter {
        override val name: String = "Disabled ($id)"
        override val pluginId: String = id
        override var indexer: IPluginIndexer
            get() = throw IllegalStateException("Importer $id is disabled")
            set(_) { throw IllegalStateException("Importer $id is disabled") }
        override val enabled: Boolean = false
        override fun canHandle(url: String): Boolean = false
        override suspend fun getWrapper(type: Type, ids: List<String>, user: User) = IdsWrapper(type, emptyFlow())
        override suspend fun importIds(ids: List<String>, type: Type, user: User, callback: suspend (List<String>) -> Unit) = Pair(false, emptyList<UserSong>())
        override suspend fun importContent(
            urls: List<String>,
            maxRetries: Int,
            aliveCheck: suspend () -> Boolean,
            userId: PlatformUUID?,
            metadata: IMetadataService.BaseMetadata?,
            onLiveOutput: suspend (String) -> Unit
        ): ProcessExecutionResult {
            onLiveOutput("Error: Importer $id is disabled and no fallback is available.")
            return ProcessExecutionResult(-1, "Importer $id is disabled", "")
        }
        override suspend fun importFavoriteCollection(type: ImportFavType, maxRetries: Int, aliveCheck: suspend () -> Boolean, userId: PlatformUUID?, onLiveOutput: suspend (String) -> Unit): ProcessExecutionResult {
            onLiveOutput("Error: Importer $id is disabled and no fallback is available.")
            return ProcessExecutionResult(-1, "Importer $id is disabled", "")
        }
        override suspend fun syncFavorites(user: User, onProgress: suspend (Double, String) -> Unit) {}
        override suspend fun search(query: String, count: Int): List<SearchResult> = emptyList()
        override suspend fun login(aliveCheck: suspend () -> Boolean, onLiveOutput: suspend (String) -> Unit): ProcessExecutionResult = ProcessExecutionResult(-1, "Importer $id is disabled", "")
        override suspend fun authorized(aliveCheck: suspend () -> Boolean): Boolean = false
        override fun tokenFileExists(): Boolean = false
    }

    suspend fun importContent(
        urls: List<String>,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID? = null,
        service: ImportBackend? = null,
        metadata: IMetadataService.BaseMetadata? = null,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        val defaultImporter = getImporter(service ?: defaultService)
        val groups = urls.groupBy { url ->
            if (defaultImporter.enabled && defaultImporter.canHandle(url)) defaultImporter
            else pluginManager.getAllImporters().find { it.enabled && it.canHandle(url) } ?: defaultImporter
        }

        var lastResult = ProcessExecutionResult.EMPTY

        for ((importer, groupUrls) in groups) {
            lastResult = importer.importContent(groupUrls, maxRetries, aliveCheck, userId, metadata, onLiveOutput)
        }

        return lastResult
    }

    suspend fun importFavoriteCollection(
        type: ImportFavType,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        service: ImportBackend = defaultService,
        userId: PlatformUUID? = null,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult = getImporter(service).importFavoriteCollection(type, maxRetries, aliveCheck, userId, onLiveOutput)

    @OptIn(ExperimentalTime::class)
    suspend fun authorized(
        aliveCheck: suspend () -> Boolean = { true },
        service: ImportBackend = defaultService
    ): Boolean = getImporter(service).authorized(aliveCheck)

    suspend fun login(
        service: ImportBackend = defaultService,
        aliveCheck: suspend () -> Boolean,
        onLiveOutput: suspend (String) -> Unit
    ) = getImporter(service).login(aliveCheck, onLiveOutput)

    fun tokenFileExists(service: ImportBackend = defaultService) = getImporter(service).tokenFileExists()
}
