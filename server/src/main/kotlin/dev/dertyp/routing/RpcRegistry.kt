package dev.dertyp.routing

import dev.dertyp.IIndexer
import dev.dertyp.Indexer
import dev.dertyp.RpcIndexer
import dev.dertyp.core.getUser
import dev.dertyp.data.User
import dev.dertyp.services.AlbumRpcService
import dev.dertyp.services.AlbumService
import dev.dertyp.services.ArtistRpcService
import dev.dertyp.services.ArtistService
import dev.dertyp.services.AuthService
import dev.dertyp.services.BackupService
import dev.dertyp.services.CustomAudioRpcService
import dev.dertyp.services.CustomAudioService
import dev.dertyp.services.DbManagementService
import dev.dertyp.services.FavSyncRpcService
import dev.dertyp.services.FavSyncService
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.IAuthService
import dev.dertyp.services.IBackupService
import dev.dertyp.services.ICustomAudioService
import dev.dertyp.services.IDbManagementService
import dev.dertyp.services.IFavSyncService
import dev.dertyp.services.IImageService
import dev.dertyp.services.ILyricsSearch
import dev.dertyp.services.ILyricsService
import dev.dertyp.services.IMirrorService
import dev.dertyp.services.IPlaybackService
import dev.dertyp.services.IPlaylistService
import dev.dertyp.services.IReleaseService
import dev.dertyp.services.IRemoteMirrorService
import dev.dertyp.services.IScheduledTaskLogService
import dev.dertyp.services.IServerStatsService
import dev.dertyp.services.ISessionService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistBackupService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.services.IUserService
import dev.dertyp.services.ImageService
import dev.dertyp.services.JwtService
import dev.dertyp.services.LyricsSearch
import dev.dertyp.services.LyricsService
import dev.dertyp.services.MirrorRpcService
import dev.dertyp.services.MirrorService
import dev.dertyp.services.PlaybackService
import dev.dertyp.services.PlaylistService
import dev.dertyp.services.ReleaseService
import dev.dertyp.services.RemoteMirrorRpcService
import dev.dertyp.services.RemoteMirrorService
import dev.dertyp.services.RpcAuthService
import dev.dertyp.services.RpcBackupService
import dev.dertyp.services.RpcPlaybackService
import dev.dertyp.services.RpcReleaseService
import dev.dertyp.services.RpcScheduledTaskLogService
import dev.dertyp.services.RpcSessionService
import dev.dertyp.services.RpcUserPlaylistBackupService
import dev.dertyp.services.RpcUserService
import dev.dertyp.services.ScheduledTaskLogService
import dev.dertyp.services.ServerStatsService
import dev.dertyp.services.SessionService
import dev.dertyp.services.SongRpcService
import dev.dertyp.services.SongService
import dev.dertyp.services.UserPlaylistBackupService
import dev.dertyp.services.UserPlaylistService
import dev.dertyp.services.UserService
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.services.metadata.MetadataDispatcherService
import dev.dertyp.services.tdn.DownloadRpcService
import dev.dertyp.services.tdn.DownloadService
import dev.dertyp.services.tdn.IDownloadService
import dev.dertyp.services.tdn.TidalDownloaderProxy
import dev.dertyp.utils.withLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.rpc.RpcServer
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.krpc.ktor.server.KrpcRoute
import org.koin.core.Koin
import kotlin.reflect.KClass

private interface ServiceRegistrar {
    fun <@Rpc T : Any> register(serviceKClass: KClass<T>, serviceFactory: () -> T)
}

fun KrpcRoute.registerPublicServices(koin: Koin) {
    val registrar = object : ServiceRegistrar {
        override fun <@Rpc T : Any> register(serviceKClass: KClass<T>, serviceFactory: () -> T) {
            registerService(serviceKClass, serviceFactory)
        }
    }
    registerPublic(koin, call, registrar)
}

fun RpcServer.registerPublicServices(koin: Koin, call: ApplicationCall) {
    val registrar = object : ServiceRegistrar {
        override fun <@Rpc T : Any> register(serviceKClass: KClass<T>, serviceFactory: () -> T) {
            registerService(serviceKClass, serviceFactory)
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
}

suspend fun KrpcRoute.registerAuthenticatedServices(koin: Koin) {
    val user = call.getUser() ?: throw IllegalArgumentException("No user found")
    val registrar = object : ServiceRegistrar {
        override fun <@Rpc T : Any> register(serviceKClass: KClass<T>, serviceFactory: () -> T) {
            registerService(serviceKClass, serviceFactory)
        }
    }
    registerAuthenticated(koin, call, user, registrar)
}

fun RpcServer.registerAuthenticatedServices(koin: Koin, call: ApplicationCall, user: User) {
    val registrar = object : ServiceRegistrar {
        override fun <@Rpc T : Any> register(serviceKClass: KClass<T>, serviceFactory: () -> T) {
            registerService(serviceKClass, serviceFactory)
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
    val lyricsSearch = koin.get<LyricsSearch>()
    val lyricsService = koin.get<LyricsService>()
    val artistService = koin.get<ArtistService>()
    val favSyncService = koin.get<FavSyncService>()
    val playlistService = koin.get<PlaylistService>()
    val downloadService = koin.get<DownloadService>()
    val userPlaylistService = koin.get<UserPlaylistService>()
    val tidalDownloaderProxy = koin.get<TidalDownloaderProxy>()
    val sessionService = koin.get<SessionService>()
    val playbackService = koin.get<PlaybackService>()
    val customAudioService = koin.get<CustomAudioService>()
    val dbManagementService = koin.get<DbManagementService>()
    val backupService = koin.get<BackupService>()
    val userPlaylistBackupService = koin.get<UserPlaylistBackupService>()
    val mirrorService = koin.get<MirrorService>()
    val remoteMirrorService = koin.get<RemoteMirrorService>()
    val scheduledTaskLogService = koin.get<ScheduledTaskLogService>()
    val releaseService = koin.get<ReleaseService>()
    val cachedMusicBrainzService = koin.get<CachedMusicBrainzService>()
    val metadataDispatcherService = koin.get<MetadataDispatcherService>()

    registrar.register(IIndexer::class) { RpcIndexer(indexer).withLogging<IIndexer>(call) }
    registrar.register(IUserService::class) { RpcUserService(user, userService, imageService).withLogging<IUserService>(call) }
    registrar.register(ISongService::class) { SongRpcService(songService = songService, user = user).withLogging<ISongService>(call) }
    registrar.register(IAlbumService::class) { AlbumRpcService(user, albumService).withLogging<IAlbumService>(call) }
    registrar.register(IImageService::class) { imageService.withLogging<IImageService>(call) }
    registrar.register(ILyricsSearch::class) { lyricsSearch.withLogging<ILyricsSearch>(call) }
    registrar.register(ILyricsService::class) { lyricsService.withLogging<ILyricsService>(call) }
    registrar.register(IArtistService::class) { ArtistRpcService(user, artistService).withLogging<IArtistService>(call) }
    registrar.register(IFavSyncService::class) { FavSyncRpcService(user, favSyncService).withLogging<IFavSyncService>(call) }
    registrar.register(IDownloadService::class) { DownloadRpcService(user, call, downloadService, tidalDownloaderProxy).withLogging<IDownloadService>(call) }
    registrar.register(IPlaylistService::class) { playlistService.withLogging<IPlaylistService>(call) }
    registrar.register(IUserPlaylistService::class) { userPlaylistService.withLogging<IUserPlaylistService>(call) }
    registrar.register(ISessionService::class) { RpcSessionService(user, sessionService).withLogging<ISessionService>(call) }
    registrar.register(IPlaybackService::class) { RpcPlaybackService(playbackService).withLogging<IPlaybackService>(call) }
    registrar.register(ICustomAudioService::class) { CustomAudioRpcService(customAudioService).withLogging<ICustomAudioService>(call) }
    registrar.register(IDbManagementService::class) { dbManagementService.withLogging<IDbManagementService>(call) }
    registrar.register(IBackupService::class) { RpcBackupService(user, backupService).withLogging<IBackupService>(call) }
    registrar.register(IUserPlaylistBackupService::class) { RpcUserPlaylistBackupService(user, userPlaylistBackupService).withLogging<IUserPlaylistBackupService>(call) }
    registrar.register(IMirrorService::class) { MirrorRpcService(user, mirrorService).withLogging<IMirrorService>(call) }
    registrar.register(IRemoteMirrorService::class) { RemoteMirrorRpcService(user, remoteMirrorService).withLogging<IRemoteMirrorService>(call) }
    registrar.register(IScheduledTaskLogService::class) { RpcScheduledTaskLogService(user, scheduledTaskLogService).withLogging<IScheduledTaskLogService>(call) }
    registrar.register(IReleaseService::class) { RpcReleaseService(user, releaseService).withLogging<IReleaseService>(call) }
    registrar.register(IMusicBrainzService::class) { cachedMusicBrainzService.withLogging<IMusicBrainzService>(call) }
    registrar.register(IMetadataService::class) { metadataDispatcherService.withLogging<IMetadataService>(call) }
}
