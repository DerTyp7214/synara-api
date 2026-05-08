package dev.dertyp.services.download

import dev.dertyp.PlatformUUID
import dev.dertyp.plugins.IDownloader
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.Service
import kotlin.time.ExperimentalTime

class DownloaderProxy(
    private val pluginManager: PluginManager
) : Service() {
    var defaultService: DownloadBackend = DownloadBackend.Tiddl

    internal fun getDownloader(service: DownloadBackend): IDownloader {
        return pluginManager.getDownloader(service.id) ?: throw IllegalStateException("Downloader ${service.id} not found")
    }

    suspend fun downloadContent(
        urls: List<String>,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID? = null,
        service: DownloadBackend? = null,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        val defaultDownloader = getDownloader(service ?: defaultService)
        val groups = urls.groupBy { url ->
            if (defaultDownloader.canHandle(url)) defaultDownloader
            else pluginManager.getAllDownloaders().find { it.canHandle(url) } ?: defaultDownloader
        }

        var lastResult = ProcessExecutionResult.EMPTY

        for ((downloader, groupUrls) in groups) {
            lastResult = downloader.downloadContent(groupUrls, maxRetries, aliveCheck, userId, onLiveOutput)
        }

        return lastResult
    }

    suspend fun downloadFavoriteCollection(
        type: DownloadFavType,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        service: DownloadBackend = defaultService,
        userId: PlatformUUID? = null,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult = getDownloader(service).downloadFavoriteCollection(type, maxRetries, aliveCheck, userId, onLiveOutput)

    @OptIn(ExperimentalTime::class)
    suspend fun authorized(
        aliveCheck: suspend () -> Boolean = { true },
        service: DownloadBackend = defaultService
    ): Boolean = getDownloader(service).authorized(aliveCheck)

    suspend fun login(
        service: DownloadBackend = defaultService,
        aliveCheck: suspend () -> Boolean,
        onLiveOutput: suspend (String) -> Unit
    ) = getDownloader(service).login(aliveCheck, onLiveOutput)

    fun tokenFileExists(service: DownloadBackend = defaultService) = getDownloader(service).tokenFileExists()
}
