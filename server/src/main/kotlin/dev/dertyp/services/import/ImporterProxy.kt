package dev.dertyp.services.import

import dev.dertyp.PlatformUUID
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.Service
import kotlin.time.ExperimentalTime

class ImporterProxy(
    private val pluginManager: PluginManager
) : Service() {
    var defaultService: ImportBackend = ImportBackend.Tiddl

    internal fun getImporter(service: ImportBackend): IImporter {
        return pluginManager.getImporter(service.id) ?: throw IllegalStateException("Importer ${service.id} not found")
    }

    suspend fun importContent(
        urls: List<String>,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID? = null,
        service: ImportBackend? = null,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        val defaultImporter = getImporter(service ?: defaultService)
        val groups = urls.groupBy { url ->
            if (defaultImporter.canHandle(url)) defaultImporter
            else pluginManager.getAllImporters().find { it.canHandle(url) } ?: defaultImporter
        }

        var lastResult = ProcessExecutionResult.EMPTY

        for ((importer, groupUrls) in groups) {
            lastResult = importer.importContent(groupUrls, maxRetries, aliveCheck, userId, onLiveOutput)
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
