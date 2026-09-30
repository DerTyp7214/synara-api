package dev.dertyp.services.import

import dev.dertyp.plugins.IPluginImportService
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val importModule = module {
    singleOf(::ImportService) { bind<IPluginImportService>() }
    singleOf(::ImporterProxy)
    singleOf(::UpcomingReleaseImportService)
}
