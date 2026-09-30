package dev.dertyp.services

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val listeningModule = module {
    singleOf(::ListenService)
    singleOf(::ScrobbleService)
    singleOf(::ListeningStatsService)
    singleOf(::PlaybackService)
    singleOf(::QueueService)
    singleOf(::RemoteControlService)
    singleOf(::ClientSettingsService)
    singleOf(::ClientRequestService)
    singleOf(::RadioService)
    singleOf(::RadioChannelService)
    singleOf(::DiscoveryService)
    singleOf(::AudioEmbeddingService)
    singleOf(::RecommendationService)
    singleOf(::RecommendationServingService)
}
