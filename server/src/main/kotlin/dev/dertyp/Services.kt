package dev.dertyp

import dev.dertyp.core.ApplicationScope
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.SearchIndexWorker
import dev.dertyp.services.ServiceLifecycle
import dev.dertyp.services.StorageService
import dev.dertyp.services.cover.CoverAssetPackService
import dev.dertyp.services.cover.CoverAutoTrigger
import dev.dertyp.services.cover.CoverGenerationService
import dev.dertyp.services.credentials.LocalCredentialProvider
import dev.dertyp.services.credentials.remote.RemoteCredentialProvider
import dev.dertyp.services.hue.HueService
import dev.dertyp.services.ui.ImporterState
import io.ktor.server.application.Application
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
    val importerState by inject<ImporterState>()
    val localCredentialProvider by inject<LocalCredentialProvider>()
    val remoteCredentialProvider by inject<RemoteCredentialProvider>()

    ServiceLifecycle.start(localCredentialProvider)
    ServiceLifecycle.start(remoteCredentialProvider)
    ServiceLifecycle.start(pluginManager)
    ServiceLifecycle.start(importerState)
    ServiceLifecycle.start(importService)
    searchIndexWorker.startService(ApplicationScope.scope)
    ServiceLifecycle.start(storageService)
    ServiceLifecycle.start(coverAssetPackService)
    ServiceLifecycle.start(coverGenerationService)
    ServiceLifecycle.start(coverAutoTrigger)
    ServiceLifecycle.start(hueService)
}
