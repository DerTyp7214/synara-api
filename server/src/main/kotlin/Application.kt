package dev.dertyp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.dertyp.core.ApplicationScope
import dev.dertyp.plugins.JmDNSPlugin
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.serializers.OffsetDateTimeAdapter
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.*
import dev.dertyp.services.tdn.DownloadService
import dev.dertyp.services.tdn.TdnService
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.time.OffsetDateTime
import java.util.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val osName = System.getProperty("os.name")
    val osVersion = System.getProperty("os.version")
    val osArch = System.getProperty("os.arch")

    log.info(
        """

        -------------------------------------------------------
        Synara API Started
        Version: ${BuildConfig.VERSION}
        Commit:  ${BuildConfig.GIT_HASH}
        Build:   ${BuildConfig.BUILD_TIME}
        Runtime: $osName ($osArch) | Kernel: $osVersion
        -------------------------------------------------------
    """.trimIndent()
    )

    install(CallLogging)
    install(JmDNSPlugin) {
        serviceName = "synara-api"
        serviceType = "_synara-api._tcp.local."
    }

    install(Koin) {
        slf4jLogger()
        modules(module {
            single<ApplicationEnvironment> { environment }

            singleOf(::Indexer)
            singleOf(::JwtService)
            singleOf(::TdnService)
            singleOf(::UserService)
            singleOf(::SongService)
            singleOf(::ImageService)
            singleOf(::AlbumService)
            singleOf(::ArtistService)
            singleOf(::StorageService)
            singleOf(::FavSyncService)
            singleOf(::DatabaseManager)
            singleOf(::PlaylistService)
            singleOf(::DownloadService)
            singleOf(::UserPlaylistService)
            singleOf(::RefreshTokenService)

            single<Gson> {
                GsonBuilder()
                    .registerTypeAdapter(OffsetDateTime::class.java, OffsetDateTimeAdapter())
                    .create()
            }

            single<RedisCacheProvider.Config> {
                if (!environment.config.propertyOrNull("redis.host")?.getString().isNullOrBlank()) {
                    RedisCacheProvider.Config().apply {
                        invalidateAt = 30.days
                        host = environment.config.propertyOrNull("redis.host")!!.getString()
                        port = environment.config.propertyOrNull("redis.port")?.getString()?.toInt() ?: port
                    }
                } else RedisCacheProvider.Config().apply { host = "none" }
            }
        })
    }

    get<DatabaseManager>().init()

    configureHTTP()
    configureRouting()
    configureDatabases()

    CoroutineScope(Dispatchers.Default).launch {
        delay(2.seconds)
        HttpClient {
            installKrpc {
                serialization { json(ApplicationScope.json) }
            }
        }
            .rpc {
                url {
                    host = "localhost"
                    port = 8080
                    encodedPath = "/song"
                }
                header(HttpHeaders.Authorization, "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJzeW5hcmEtYXBpIiwiaXNzIjoic3luYXJhIiwidXNlcm5hbWUiOiJ0ZXN0LWlkIiwiZXhwIjoxNzY3NzQ2MTIwfQ.O6p8E_YfNrmrUMGTO70WkxGy7wrCBgAYOrrxfg5Zrso")
            }
            .withService<ISongService>()
            .allSongs(0, 1, true, UUID.fromString("3c73a2b0-c65c-4aa8-a8f8-5b56c1276eb3"))
    }
}