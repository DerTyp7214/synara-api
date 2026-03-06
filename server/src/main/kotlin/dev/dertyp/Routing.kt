package dev.dertyp

import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.getUser
import dev.dertyp.services.*
import dev.dertyp.services.tdn.DownloadRpcService
import dev.dertyp.services.tdn.DownloadService
import dev.dertyp.services.tdn.IDownloadService
import dev.dertyp.services.tdn.TidalDownloaderProxy
import dev.dertyp.utils.withLogging
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktoropenapi.route
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.serialization.kotlinx.protobuf.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.server.websocket.*
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import org.koin.ktor.ext.inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
fun Application.configureRouting() {
    install(ContentNegotiation) {
        json(ApplicationScope.json)
        protobuf()
    }
    install(SSE)
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    install(Krpc) {
        serialization {
            cbor(ApplicationScope.cbor)
        }
    }
    install(OpenApi)
    install(Compression)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }
    routing {
        route("api.json") {
            openApi()
        }
        route("swagger") {
            swaggerUI("/api.json") {

            }
        }

        val indexer by inject<Indexer>()
        val jwtService by inject<JwtService>()
        val userService by inject<UserService>()
        val authService by inject<AuthService>()
        val songService by inject<SongService>()
        val albumService by inject<AlbumService>()
        val imageService by inject<ImageService>()
        val lyricsSearch by inject<LyricsSearch>()
        val artistService by inject<ArtistService>()
        val favSyncService by inject<FavSyncService>()
        val playlistService by inject<PlaylistService>()
        val downloadService by inject<DownloadService>()
        val serverStatsService by inject<ServerStatsService>()
        val userPlaylistService by inject<UserPlaylistService>()
        val tidalDownloaderProxy by inject<TidalDownloaderProxy>()
        val sessionService by inject<SessionService>()
        val playbackService by inject<PlaybackService>()
        val customAudioService by inject<CustomAudioService>()
        val dbManagementService by inject<DbManagementService>()
        val backupService by inject<BackupService>()

        rpc("/rpc") {
            registerService<IServerStatsService> { serverStatsService.withLogging<IServerStatsService>() }
        }

        rpc("/rpc/auth") {
            registerService<IAuthService> { RpcAuthService(call, authService, sessionService, jwtService).withLogging<IAuthService>() }
        }

        jwtService.authenticated(this) {
            rpc("/rpc/services") {
                val user = call.getUser() ?: throw IllegalArgumentException("No user found")

                registerService<IIndexer> { RpcIndexer(indexer).withLogging<IIndexer>() }
                registerService<IUserService> { RpcUserService(user, userService).withLogging<IUserService>() }
                registerService<ISongService> { SongRpcService(songService = songService, user = user).withLogging<ISongService>() }
                registerService<IAlbumService> { albumService.withLogging<IAlbumService>() }
                registerService<IImageService> { imageService.withLogging<IImageService>() }
                registerService<ILyricsSearch> { lyricsSearch.withLogging<ILyricsSearch>() }
                registerService<IArtistService> { artistService.withLogging<IArtistService>() }
                registerService<IFavSyncService> { FavSyncRpcService(user, favSyncService).withLogging<IFavSyncService>() }
                registerService<IDownloadService> { DownloadRpcService(user, call, downloadService, tidalDownloaderProxy).withLogging<IDownloadService>() }
                registerService<IPlaylistService> { playlistService.withLogging<IPlaylistService>() }
                registerService<IUserPlaylistService> { userPlaylistService.withLogging<IUserPlaylistService>() }
                registerService<ISessionService> { RpcSessionService(user, sessionService).withLogging<ISessionService>() }
                registerService<IPlaybackService> { RpcPlaybackService(playbackService).withLogging<IPlaybackService>() }
                registerService<ICustomAudioService> { CustomAudioRpcService(customAudioService).withLogging<ICustomAudioService>() }
                registerService<IDbManagementService> { dbManagementService.withLogging<IDbManagementService>() }
                registerService<IBackupService> { RpcBackupService(user, backupService).withLogging<IBackupService>() }
            }
        }

        jwtService.authenticate(this)
    }
}
