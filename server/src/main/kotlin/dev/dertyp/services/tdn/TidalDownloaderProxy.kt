package dev.dertyp.services.tdn

import dev.dertyp.services.Service
import java.io.File
import kotlin.time.ExperimentalTime

class TidalDownloaderProxy(
    private val tdnService: TdnService,
    private val tiddlService: TiddlService
) : Service() {
    var defaultService: TidalDownloadService = TidalDownloadService.Tdn

    suspend fun downloadContent(
        urls: List<String>,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        service: TidalDownloadService = defaultService,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult = when (service) {
        TidalDownloadService.Tdn -> tdnService.downloadContent(urls, maxRetries, aliveCheck, onLiveOutput)
        TidalDownloadService.Tiddl -> tiddlService.downloadContent(urls, maxRetries, aliveCheck, onLiveOutput)
    }

    suspend fun downloadFavoriteCollection(
        type: TidalFavType,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        service: TidalDownloadService = defaultService,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult = when (service) {
        TidalDownloadService.Tdn -> tdnService.downloadFavoriteCollection(type, maxRetries, aliveCheck, onLiveOutput)
        TidalDownloadService.Tiddl -> tiddlService.downloadFavoriteCollection(
            type,
            maxRetries,
            aliveCheck,
            onLiveOutput
        )
    }

    @OptIn(ExperimentalTime::class)
    suspend fun authorized(
        aliveCheck: suspend () -> Boolean = { true },
        service: TidalDownloadService = defaultService
    ): Boolean = when (service) {
        TidalDownloadService.Tdn -> tdnService.authorized(aliveCheck)
        TidalDownloadService.Tiddl -> tiddlService.authorized(aliveCheck)
    }

    suspend fun login(
        service: TidalDownloadService = defaultService,
        aliveCheck: suspend () -> Boolean,
        onLiveOutput: suspend (String) -> Unit
    ) = when (service) {
        TidalDownloadService.Tdn -> tdnService.login(aliveCheck, onLiveOutput)
        TidalDownloadService.Tiddl -> tiddlService.login(aliveCheck, onLiveOutput)
    }

    fun tokenFileExists(service: TidalDownloadService = defaultService) = when (service) {
        TidalDownloadService.Tdn -> {
            val homeDir = System.getProperty("user.home")
            val tdnTokenJson = File(homeDir, ".config/tidal_dl_ng/token.json")
            tdnTokenJson.exists()
        }

        TidalDownloadService.Tiddl -> {
            val homeDir = System.getProperty("user.home")
            val tdnTokenJson = File(homeDir, ".tiddl/auth.json")
            tdnTokenJson.exists()
        }
    }
}