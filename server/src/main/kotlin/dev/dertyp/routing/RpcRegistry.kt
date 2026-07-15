package dev.dertyp.routing

import dev.dertyp.IIndexer
import dev.dertyp.Indexer
import dev.dertyp.RpcIndexer
import dev.dertyp.core.getUser
import dev.dertyp.data.User
import dev.dertyp.services.*
import dev.dertyp.services.import.IImportService
import dev.dertyp.services.import.ImportRpcService
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.import.ImporterProxy
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.services.metadata.MetadataDispatcherService
import dev.dertyp.services.schedule.RpcScheduledTaskConfigurationService
import dev.dertyp.services.schedule.ScheduleService
import dev.dertyp.services.schedule.ScheduledTaskConfigurationService
import dev.dertyp.services.sync.ListenBrainzService
import dev.dertyp.services.sync.RpcListenBrainzService
import dev.dertyp.core.principalUsername
import dev.dertyp.utils.withAuthorization
import dev.dertyp.utils.withCaching
import dev.dertyp.utils.withLogging
import dev.dertyp.utils.withMetrics
import io.ktor.server.application.ApplicationCall
import kotlinx.rpc.RpcServer
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.krpc.ktor.server.KrpcRoute
import org.koin.core.Koin
import kotlin.reflect.KClass

private interface ServiceRegistrar {
    fun <@Rpc T : Any> register(serviceKClass: KClass<T>, serviceFactory: () -> T)
}

private fun <T : Any> T.wrap(interfaceClass: KClass<T>, username: String, collector: RpcMetricsCollector): T {
    val cached = this.withCaching(interfaceClass.java)
    return if (collector.enabled) cached.withMetrics(interfaceClass.java, username, collector) else cached
}

fun KrpcRoute.registerPublicServices(koin: Koin) {
    val collector = koin.get<RpcMetricsCollector>()
    val username = call.principalUsername ?: ""
    val registrar = object : ServiceRegistrar {
        override fun <@Rpc T : Any> register(serviceKClass: KClass<T>, serviceFactory: () -> T) {
            registerService(serviceKClass) { serviceFactory().wrap(serviceKClass, username, collector) }
        }
    }
    registerPublic(koin, call, registrar)
}

fun RpcServer.registerPublicServices(koin: Koin, call: ApplicationCall) {
    val collector = koin.get<RpcMetricsCollector>()
    val username = call.principalUsername ?: ""
    val registrar = object : ServiceRegistrar {
        override fun <@Rpc T : Any> register(serviceKClass: KClass<T>, serviceFactory: () -> T) {
            registerService(serviceKClass) { serviceFactory().wrap(serviceKClass, username, collector) }
        }
    }
    registerPublic(koin, call, registrar)
}

private fun registerPublic(koin: Koin, call: ApplicationCall, registrar: ServiceRegistrar) {
    val serverStatsService = koin.get<ServerStatsService>()
    val authService = koin.get<AuthService>()
    val sessionService = koin.get<SessionService>()
    val jwtService = koin.get<JwtService>()

    registrar.register(IServerStatsService::class) { serverStatsService.withLogging<IServerStatsService>(call) }
    registrar.register(IAuthService::class) { RpcAuthService(call, authService, sessionService, jwtService).withLogging<IAuthService>(call) }
    registrar.register(IHandshakeService::class) { HandshakeService(call).withLogging<IHandshakeService>(call) }
}

suspend fun KrpcRoute.registerAuthenticatedServices(koin: Koin) {
    val user = call.getUser() ?: throw IllegalArgumentException("No user found")
    val collector = koin.get<RpcMetricsCollector>()
    val username = call.principalUsername ?: ""
    val registrar = object : ServiceRegistrar {
        override fun <@Rpc T : Any> register(serviceKClass: KClass<T>, serviceFactory: () -> T) {
            registerService(serviceKClass) { serviceFactory().wrap(serviceKClass, username, collector) }
        }
    }
    registerAuthenticated(koin, call, user, registrar)
}

fun RpcServer.registerAuthenticatedServices(koin: Koin, call: ApplicationCall, user: User) {
    val collector = koin.get<RpcMetricsCollector>()
    val username = call.principalUsername ?: ""
    val registrar = object : ServiceRegistrar {
        override fun <@Rpc T : Any> register(serviceKClass: KClass<T>, serviceFactory: () -> T) {
            registerService(serviceKClass) { serviceFactory().wrap(serviceKClass, username, collector) }
        }
    }
    registerAuthenticated(koin, call, user, registrar)
}

private fun registerAuthenticated(koin: Koin, call: ApplicationCall, user: User, registrar: ServiceRegistrar) {
    val indexer = koin.get<Indexer>()
    val userService = koin.get<UserService>()
    val songService = koin.get<SongService>()
    val albumService = koin.get<AlbumService>()
    val imageService = koin.get<ImageService>()
    val animatedImageService = koin.get<AnimatedImageService>()
    val lyricsSearch = koin.get<LyricsSearch>()
    val lyricsService = koin.get<LyricsService>()
    val artistService = koin.get<ArtistService>()
    val importService = koin.get<ImportService>()
    val favSyncService = koin.get<FavSyncService>()
    val playlistService = koin.get<PlaylistService>()
    val userPlaylistService = koin.get<UserPlaylistService>()
    val collectionService = koin.get<CollectionService>()
    val importerProxy = koin.get<ImporterProxy>()
    val sessionService = koin.get<SessionService>()
    val playbackService = koin.get<PlaybackService>()
    val customAudioService = koin.get<CustomAudioService>()
    val dbManagementService = koin.get<DbManagementService>()
    val backupService = koin.get<BackupService>()
    val userPlaylistBackupService = koin.get<UserPlaylistBackupService>()
    val mirrorService = koin.get<MirrorService>()
    val remoteMirrorService = koin.get<RemoteMirrorService>()
    val scheduledTaskLogService = koin.get<ScheduledTaskLogService>()
    val scheduledTaskConfigurationService = koin.get<ScheduledTaskConfigurationService>()
    val scheduleService = koin.get<ScheduleService>()
    val releaseService = koin.get<ReleaseService>()
    val audioAnalysisService = koin.get<AudioAnalysisService>()
    val discoveryService = koin.get<DiscoveryService>()
    val cachedMusicBrainzService = koin.get<CachedMusicBrainzService>()
    val metadataDispatcherService = koin.get<MetadataDispatcherService>()
    val rpcMetricsService = koin.get<RpcMetricsService>()
    val listenBrainzService = koin.get<ListenBrainzService>()
    val scrobbleService = koin.get<ScrobbleService>()
    val recommendationServingService = koin.get<RecommendationServingService>()
    val radioService = koin.get<RadioService>()
    val apiKeyService = koin.get<ApiKeyService>()

    registrar.register(IIndexer::class) { RpcIndexer(indexer, user).withAuthorization<IIndexer>(user).withLogging<IIndexer>(call) }
    registrar.register(IUserService::class) { RpcUserService(user, userService, imageService).withAuthorization<IUserService>(user).withLogging<IUserService>(call) }
    registrar.register(ISongService::class) { SongRpcService(songService = songService, user = user).withAuthorization<ISongService>(user).withLogging<ISongService>(call) }
    registrar.register(IAlbumService::class) { AlbumRpcService(user, albumService).withAuthorization<IAlbumService>(user).withLogging<IAlbumService>(call) }
    registrar.register(IImageService::class) { ImageRpcService(user, imageService).withAuthorization<IImageService>(user).withLogging<IImageService>(call) }
    registrar.register(IAnimatedImageService::class) { AnimatedImageRpcService(animatedImageService).withAuthorization<IAnimatedImageService>(user).withLogging<IAnimatedImageService>(call) }
    registrar.register(IAudioAnalysisService::class) { audioAnalysisService.withAuthorization<IAudioAnalysisService>(user).withLogging<IAudioAnalysisService>(call) }
    registrar.register(IDiscoveryService::class) { DiscoveryRpcService(user, discoveryService).withAuthorization<IDiscoveryService>(user).withLogging<IDiscoveryService>(call) }
    registrar.register(ILyricsSearch::class) { lyricsSearch.withAuthorization<ILyricsSearch>(user).withLogging<ILyricsSearch>(call) }
    registrar.register(ILyricsService::class) { lyricsService.withAuthorization<ILyricsService>(user).withLogging<ILyricsService>(call) }
    registrar.register(IArtistService::class) { ArtistRpcService(user, artistService).withAuthorization<IArtistService>(user).withLogging<IArtistService>(call) }
    registrar.register(IFavSyncService::class) { FavSyncRpcService(user, favSyncService).withAuthorization<IFavSyncService>(user).withLogging<IFavSyncService>(call) }
    registrar.register(IImportService::class) { ImportRpcService(user, call, importService, importerProxy).withAuthorization<IImportService>(user).withLogging<IImportService>(call) }
    registrar.register(IPlaylistService::class) { playlistService.withAuthorization<IPlaylistService>(user).withLogging<IPlaylistService>(call) }
    registrar.register(IUserPlaylistService::class) { userPlaylistService.withAuthorization<IUserPlaylistService>(user).withLogging<IUserPlaylistService>(call) }
    registrar.register(ICollectionService::class) { RpcCollectionService(user, collectionService).withAuthorization<ICollectionService>(user).withLogging<ICollectionService>(call) }
    registrar.register(ISessionService::class) { RpcSessionService(user, sessionService).withAuthorization<ISessionService>(user).withLogging<ISessionService>(call) }
    registrar.register(IPlaybackService::class) { RpcPlaybackService(playbackService).withAuthorization<IPlaybackService>(user).withLogging<IPlaybackService>(call) }
    registrar.register(ICustomAudioService::class) { CustomAudioRpcService(customAudioService).withAuthorization<ICustomAudioService>(user).withLogging<ICustomAudioService>(call) }
    registrar.register(IDbManagementService::class) { dbManagementService.withAuthorization<IDbManagementService>(user).withLogging<IDbManagementService>(call) }
    registrar.register(IBackupService::class) { RpcBackupService(user, backupService).withAuthorization<IBackupService>(user).withLogging<IBackupService>(call) }
    registrar.register(IUserPlaylistBackupService::class) { RpcUserPlaylistBackupService(user, userPlaylistBackupService).withAuthorization<IUserPlaylistBackupService>(user).withLogging<IUserPlaylistBackupService>(call) }
    registrar.register(IMirrorService::class) { MirrorRpcService(mirrorService).withAuthorization<IMirrorService>(user).withLogging<IMirrorService>(call) }
    registrar.register(IRemoteMirrorService::class) { RemoteMirrorRpcService(user, remoteMirrorService).withAuthorization<IRemoteMirrorService>(user).withLogging<IRemoteMirrorService>(call) }
    registrar.register(IScheduledTaskLogService::class) { RpcScheduledTaskLogService(user, scheduledTaskLogService).withAuthorization<IScheduledTaskLogService>(user).withLogging<IScheduledTaskLogService>(call) }
    registrar.register(IScheduledTaskConfigurationService::class) { RpcScheduledTaskConfigurationService(scheduledTaskConfigurationService, scheduleService).withAuthorization<IScheduledTaskConfigurationService>(user).withLogging<IScheduledTaskConfigurationService>(call) }
    registrar.register(IReleaseService::class) { RpcReleaseService(user, releaseService).withAuthorization<IReleaseService>(user).withLogging<IReleaseService>(call) }
    registrar.register(IMusicBrainzService::class) { cachedMusicBrainzService.withAuthorization<IMusicBrainzService>(user).withLogging<IMusicBrainzService>(call) }
    registrar.register(IMetadataService::class) { metadataDispatcherService.withAuthorization<IMetadataService>(user).withLogging<IMetadataService>(call) }
    registrar.register(IRpcMetricsService::class) { rpcMetricsService.withAuthorization<IRpcMetricsService>(user).withLogging<IRpcMetricsService>(call) }
    registrar.register(IListenBrainzService::class) { RpcListenBrainzService(user, listenBrainzService).withAuthorization<IListenBrainzService>(user).withLogging<IListenBrainzService>(call) }
    registrar.register(IScrobbleService::class) { RpcScrobbleService(user, scrobbleService).withAuthorization<IScrobbleService>(user).withLogging<IScrobbleService>(call) }
    registrar.register(IRecommendationService::class) { RpcRecommendationService(user, recommendationServingService).withAuthorization<IRecommendationService>(user).withLogging<IRecommendationService>(call) }
    registrar.register(IRadioService::class) { RadioRpcService(user, radioService).withAuthorization<IRadioService>(user).withLogging<IRadioService>(call) }
    registrar.register(IApiKeyService::class) { RpcApiKeyService(user, apiKeyService).withAuthorization<IApiKeyService>(user).withLogging<IApiKeyService>(call) }
}
