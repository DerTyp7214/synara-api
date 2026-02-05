package dev.dertyp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.dertyp.plugins.JmDNSPlugin
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.serializers.DurationAdapter
import dev.dertyp.serializers.LocalDateAdapter
import dev.dertyp.serializers.OffsetDateTimeAdapter
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.*
import dev.dertyp.services.schedule.CronPresets
import dev.dertyp.services.schedule.ScheduleService
import dev.dertyp.services.schedule.ScheduledTask
import dev.dertyp.services.tdn.DownloadService
import dev.dertyp.services.tdn.TdnService
import dev.dertyp.services.tdn.TidalDownloaderProxy
import dev.dertyp.services.tdn.TiddlService
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

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
            singleOf(::AuthService)
            singleOf(::SongService)
            singleOf(::TiddlService)
            singleOf(::ImageService)
            singleOf(::AlbumService)
            singleOf(::LyricsSearch)
            singleOf(::ArtistService)
            singleOf(::StorageService)
            singleOf(::FavSyncService)
            singleOf(::DatabaseManager)
            singleOf(::PlaylistService)
            singleOf(::DownloadService)
            singleOf(::ScheduleService)
            singleOf(::ServerStatsService)
            singleOf(::UserPlaylistService)
            singleOf(::RefreshTokenService)
            singleOf(::TidalDownloaderProxy)
            singleOf(::SessionService)
            singleOf(::PlaybackService)

            single<Gson> {
                GsonBuilder()
                    .registerTypeAdapter(OffsetDateTime::class.java, OffsetDateTimeAdapter())
                    .registerTypeAdapter(LocalDate::class.java, LocalDateAdapter())
                    .registerTypeAdapter(Duration::class.java, DurationAdapter())
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

    val scheduleService = get<ScheduleService>()
    val sessionService = get<SessionService>()

    scheduleService.schedule(
        ScheduledTask(
            trigger = CronPresets.dailyAt(0, 0),
            task = { sessionService.cleanupOldSessions() }
        )
    )

    CoroutineScope(Dispatchers.IO).launch {
        scheduleService.startService()
    }

    configureHTTP()
    configureRouting()
    configureDatabases()
}
