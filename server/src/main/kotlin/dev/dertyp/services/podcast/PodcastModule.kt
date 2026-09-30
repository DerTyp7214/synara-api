package dev.dertyp.services.podcast

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val podcastModule = module {
    singleOf(::PodcastHttp)
    singleOf(::PodcastService)
    singleOf(::PodcastFeedService)
    singleOf(::PodcastLocalScanService)
    singleOf(::PodcastImportService)
    singleOf(::PodcastMaintenanceService)
    singleOf(::PodcastStreamService)
    singleOf(::PodcastIndexService)
}
