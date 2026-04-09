package dev.dertyp.routing

import dev.dertyp.IIndexer
import dev.dertyp.Indexer
import dev.dertyp.RpcIndexer
import dev.dertyp.core.getUser
import dev.dertyp.data.User
import dev.dertyp.services.*
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.services.metadata.RpcMusicBrainzService
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
    val musicBrainzService = koin.get<MusicBrainzService>()
    val musicBrainzCacheService = koin.get<MusicBrainzCacheService>()

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
    registrar.register(IMusicBrainzService::class) { RpcMusicBrainzService(musicBrainzService, musicBrainzCacheService).withLogging<IMusicBrainzService>(call) }
}
