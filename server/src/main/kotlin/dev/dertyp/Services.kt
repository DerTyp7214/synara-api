package dev.dertyp

import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.SearchIndexWorker
import dev.dertyp.services.StorageService
import dev.dertyp.services.cover.CoverAssetPackService
import dev.dertyp.services.cover.CoverAutoTrigger
import dev.dertyp.services.cover.CoverGenerationService
import dev.dertyp.services.hue.HueService
import io.ktor.server.application.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject

fun Application.configureServices() {
    val pluginManager by inject<PluginManager>()
    val importService by inject<ImportService>()
    val searchIndexWorker by inject<SearchIndexWorker>()
    val storageService by inject<StorageService>()
    val coverAssetPackService by inject<CoverAssetPackService>()
    val coverGenerationService by inject<CoverGenerationService>()
    val coverAutoTrigger by inject<CoverAutoTrigger>()
    val hueService by inject<HueService>()

    CoroutineScope(Dispatchers.IO).launch {
        launch { pluginManager.startService() }
        launch { importService.startService() }
        launch { searchIndexWorker.startService(this) }
        launch { storageService.startService() }
        launch { coverAssetPackService.startService() }
        launch { coverGenerationService.startService() }
        launch { coverAutoTrigger.startService() }
        launch { hueService.startService() }
    }
}
