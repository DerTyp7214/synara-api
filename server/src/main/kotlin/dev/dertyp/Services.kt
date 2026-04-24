package dev.dertyp

import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.download.DownloadService
import io.ktor.server.application.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject

fun Application.configureServices() {
    val pluginManager by inject<PluginManager>()
    val downloadService by inject<DownloadService>()

    CoroutineScope(Dispatchers.IO).launch {
        launch { pluginManager.startService() }
        launch { downloadService.startService() }
    }
}
