package dev.dertyp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.dertyp.plugins.JmDNSPlugin
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.serializers.ByteArrayISO8859TypeAdapter
import dev.dertyp.serializers.DurationAdapter
import dev.dertyp.serializers.LocalDateAdapter
import dev.dertyp.serializers.OffsetDateTimeAdapter
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.*
import dev.dertyp.services.schedule.CronPresets
import dev.dertyp.services.schedule.ScheduleService
import dev.dertyp.services.schedule.ScheduledTask
import dev.dertyp.services.schedule.TaskCompletionTrigger
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
            singleOf(::CustomAudioService)
            singleOf(::DbManagementService)
            singleOf(::BackupService)

            single<Gson> {
                GsonBuilder()
                    .registerTypeAdapter(OffsetDateTime::class.java, OffsetDateTimeAdapter())
                    .registerTypeAdapter(ByteArray::class.java, ByteArrayISO8859TypeAdapter())
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
    val imageService = get<ImageService>()
    val artistService = get<ArtistService>()
    val albumService = get<AlbumService>()
    val backupService = get<BackupService>()

    scheduleService.schedule(
        ScheduledTask(
            name = "Database Backup",
            trigger = CronPresets.dailyAt(19, 27),
            task = { backupService.createBackup() }
        )
    )

    scheduleService.schedule(
        ScheduledTask(
            name = "Session Cleanup",
            trigger = CronPresets.dailyAt(0, 0),
            task = { sessionService.cleanupOldSessions() }
        )
    )

    val cleanAlbumTask = scheduleService.schedule(
        ScheduledTask(
            name = "Delete Empty Albums",
            trigger = CronPresets.dailyAt(0, 0),
            task = { albumService.deleteEmptyAlbums() }
        )
    )

    val cleanArtistsTask = scheduleService.schedule(
        ScheduledTask(
            name = "Delete Unreferenced Artists",
            trigger = TaskCompletionTrigger(cleanAlbumTask.id),
            task = { artistService.deleteUnreferencedArtists() }
        )
    )

    scheduleService.schedule(
        ScheduledTask(
            name = "Delete Unreferenced Images",
            trigger = TaskCompletionTrigger(cleanArtistsTask.id),
            task = { imageService.deleteUnreferencedImages() }
        )
    )

    CoroutineScope(Dispatchers.IO).launch {
        scheduleService.startService()
    }

    configureHTTP()
    configureRouting()
    configureDatabases()
}
