package dev.dertyp.routing

import dev.dertyp.IIndexer
import dev.dertyp.RpcIndexer
import dev.dertyp.core.clientInfo
import dev.dertyp.core.getSessionId
import dev.dertyp.core.getUser
import dev.dertyp.routing.rest.*
import dev.dertyp.services.*
import dev.dertyp.services.cover.RpcCoverGenerationService
import dev.dertyp.services.hue.RpcHueService
import dev.dertyp.services.import.IImportService
import dev.dertyp.services.import.ImportRpcService
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.services.metadata.MetadataDispatcherService
import dev.dertyp.services.podcast.RpcPodcastService
import dev.dertyp.services.schedule.RpcScheduledTaskConfigurationService
import dev.dertyp.services.subsonic.RpcSubsonicCredentialService
import dev.dertyp.services.sync.RpcListenBackupService
import dev.dertyp.services.sync.RpcListenBrainzService
import dev.dertyp.services.ui.RpcUiService
import dev.dertyp.utils.withAuthorization
import io.ktor.server.routing.Route
import org.koin.core.Koin

fun Route.registerPublicRestServices(koin: Koin) {
    registerIServerStatsServiceRest { koin.get<ServerStatsService>() }
    registerIAuthServiceRest { RpcAuthService(call, koin.get(), koin.get(), koin.get(), koin.get()) }
    registerIHandshakeServiceRest { HandshakeService(call) }
    registerIImageServiceRest(authenticated = true) {
        ImageRpcService(call.getUser(), koin.get<ImageService>())
    }
    registerIAnimatedImageServiceRest(authenticated = true) {
        AnimatedImageRpcService(koin.get<AnimatedImageService>())
    }
}

fun Route.registerAuthenticatedRestServices(koin: Koin) {
    registerIUiServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcUiService(user, call.clientInfo, call, koin.get()).withAuthorization<IUiService>(user)
    }
    registerIIndexerRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcIndexer(koin.get(), user).withAuthorization<IIndexer>(user)
    }
    registerIUserServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcUserService(user, koin.get(), koin.get()).withAuthorization<IUserService>(user)
    }
    registerISongServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        SongRpcService(songService = koin.get(), user = user, client = call.clientInfo).withAuthorization<ISongService>(user)
    }
    registerIAlbumServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        AlbumRpcService(user, koin.get()).withAuthorization<IAlbumService>(user)
    }
    registerILyricsSearchRest(authenticated = true) {
        val user = call.getUser()
        koin.get<LyricsSearch>().withAuthorization<ILyricsSearch>(user)
    }
    registerILyricsServiceRest(authenticated = true) {
        val user = call.getUser()
        koin.get<LyricsService>().withAuthorization<ILyricsService>(user)
    }
    registerIArtistServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        ArtistRpcService(user, koin.get()).withAuthorization<IArtistService>(user)
    }
    registerIAudioAnalysisServiceRest(authenticated = true) {
        val user = call.getUser()
        koin.get<AudioAnalysisService>().withAuthorization<IAudioAnalysisService>(user)
    }
    registerIDiscoveryServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        DiscoveryRpcService(user, koin.get()).withAuthorization<IDiscoveryService>(user)
    }
    registerIFavSyncServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        FavSyncRpcService(user, koin.get()).withAuthorization<IFavSyncService>(user)
    }
    registerIImportServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        ImportRpcService(user, call, koin.get(), koin.get()).withAuthorization<IImportService>(user)
    }
    registerIPlaylistServiceRest(authenticated = true) {
        val user = call.getUser()
        koin.get<PlaylistService>().withAuthorization<IPlaylistService>(user)
    }
    registerIUserPlaylistServiceRest(authenticated = true) {
        val user = call.getUser()
        koin.get<UserPlaylistService>().withAuthorization<IUserPlaylistService>(user)
    }
    registerICollectionServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcCollectionService(user, koin.get()).withAuthorization<ICollectionService>(user)
    }
    registerICoverGenerationServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcCoverGenerationService(user, koin.get()).withAuthorization<ICoverGenerationService>(user)
    }
    registerIHueServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcHueService(user, koin.get()).withAuthorization<IHueService>(user)
    }
    registerISessionServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcSessionService(user, koin.get()).withAuthorization<ISessionService>(user)
    }
    registerIPlaybackServiceRest(authenticated = true) {
        val user = call.getUser()
        RpcPlaybackService(koin.get()).withAuthorization<IPlaybackService>(user)
    }
    registerIQueueServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcQueueService(user, call.getSessionId(), koin.get(), koin.get(), koin.get()).withAuthorization<IQueueService>(user)
    }
    registerIClientSettingsServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcClientSettingsService(user, koin.get()).withAuthorization<IClientSettingsService>(user)
    }
    registerITimecodeTagServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcTimecodeTagService(user, koin.get()).withAuthorization<ITimecodeTagService>(user)
    }
    registerIPodcastServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcPodcastService(user, koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get()).withAuthorization<IPodcastService>(user)
    }
    registerIClientRequestServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcClientRequestService(call.getSessionId() ?: throw IllegalArgumentException("No session found"), koin.get())
            .withAuthorization<IClientRequestService>(user)
    }
    registerICustomAudioServiceRest(authenticated = true) {
        val user = call.getUser()
        CustomAudioRpcService(koin.get()).withAuthorization<ICustomAudioService>(user)
    }
    registerIDbManagementServiceRest(authenticated = true) {
        val user = call.getUser()
        koin.get<DbManagementService>().withAuthorization<IDbManagementService>(user)
    }
    registerIBackupServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcBackupService(user, koin.get()).withAuthorization<IBackupService>(user)
    }
    registerIUserPlaylistBackupServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcUserPlaylistBackupService(user, koin.get()).withAuthorization<IUserPlaylistBackupService>(user)
    }
    registerIMirrorServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        MirrorRpcService(koin.get()).withAuthorization<IMirrorService>(user)
    }
    registerIRemoteMirrorServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RemoteMirrorRpcService(user, koin.get()).withAuthorization<IRemoteMirrorService>(user)
    }
    registerIScheduledTaskLogServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcScheduledTaskLogService(user, koin.get()).withAuthorization<IScheduledTaskLogService>(user)
    }
    registerIScheduledTaskConfigurationServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcScheduledTaskConfigurationService(koin.get(), koin.get()).withAuthorization<IScheduledTaskConfigurationService>(user)
    }
    registerIReleaseServiceRest(authenticated = true) {
        val user = call.getUser()
        RpcReleaseService(user, koin.get()).withAuthorization<IReleaseService>(user)
    }
    registerIMusicBrainzServiceRest(authenticated = true) {
        val user = call.getUser()
        koin.get<CachedMusicBrainzService>().withAuthorization<IMusicBrainzService>(user)
    }
    registerIMetadataServiceRest(authenticated = true) {
        val user = call.getUser()
        koin.get<MetadataDispatcherService>().withAuthorization<IMetadataService>(user)
    }
    registerIListenBrainzServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcListenBrainzService(user, koin.get()).withAuthorization<IListenBrainzService>(user)
    }
    registerIListenBackupServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcListenBackupService(koin.get()).withAuthorization<IListenBackupService>(user)
    }
    registerIScrobbleServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcScrobbleService(user, koin.get()).withAuthorization<IScrobbleService>(user)
    }
    registerIListeningStatsServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcListeningStatsService(user, koin.get()).withAuthorization<IListeningStatsService>(user)
    }
    registerIRecommendationServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcRecommendationService(user, koin.get()).withAuthorization<IRecommendationService>(user)
    }
    registerIRadioServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RadioRpcService(user, koin.get()).withAuthorization<IRadioService>(user)
    }
    registerIApiKeyServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcApiKeyService(user, koin.get()).withAuthorization<IApiKeyService>(user)
    }
    registerIRadioChannelServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcRadioChannelService(user, koin.get(), koin.get()).withAuthorization<IRadioChannelService>(user)
    }
    registerISubsonicCredentialServiceRest(authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcSubsonicCredentialService(user, koin.get()).withAuthorization<ISubsonicCredentialService>(user)
    }
}
