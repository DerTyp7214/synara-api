package dev.dertyp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.dertyp.core.logTask
import dev.dertyp.plugins.JmDNSPlugin
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.serializers.ByteArrayISO8859TypeAdapter
import dev.dertyp.serializers.DurationAdapter
import dev.dertyp.serializers.LocalDateAdapter
import dev.dertyp.serializers.OffsetDateTimeAdapter
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.*
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.services.schedule.*
import dev.dertyp.services.tdn.DownloadService
import dev.dertyp.services.tdn.TdnService
import dev.dertyp.services.tdn.TidalDownloaderProxy
import dev.dertyp.services.tdn.TiddlService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.calllogging.CallLogging
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

    val application = this
    install(Koin) {
        slf4jLogger()
        modules(module {
            single<Application> { application }
            single<ApplicationEnvironment> { environment }
            single { environment.config }

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
            singleOf(::LibraryMergeService)
            singleOf(::DownloadService)
            singleOf(::ScheduleService)
            singleOf(::ServerStatsService)
            singleOf(::UserPlaylistService)
            singleOf(::RefreshTokenService)
            singleOf(::ScheduledTaskLogService)
            singleOf(::TidalDownloaderProxy)
            singleOf(::SessionService)
            singleOf(::PlaybackService)
            singleOf(::CustomAudioService)
            singleOf(::ReverseProxyService)
            singleOf(::DbManagementService)
            singleOf(::BackupService)
            singleOf(::UserPlaylistBackupService)
            singleOf(::MetadataFetchingService)
            singleOf(::MirrorService)
            singleOf(::RemoteMirrorService)
            singleOf(::MusicBrainzService)
            singleOf(::MusicBrainzWorker)
            singleOf(::AutoTranscodeWorker)
            singleOf(::CustomMigrationService)

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

    val customMigrationService = get<CustomMigrationService>()
    CoroutineScope(Dispatchers.IO).launch {
        customMigrationService.runMigrations()
    }

    val scheduleService = get<ScheduleService>()
    val sessionService = get<SessionService>()
    val imageService = get<ImageService>()
    val libraryMergeService = get<LibraryMergeService>()
    val artistService = get<ArtistService>()
    val albumService = get<AlbumService>()
    val backupService = get<BackupService>()
    val userPlaylistBackupService = get<UserPlaylistBackupService>()
    val reverseProxyService = get<ReverseProxyService>()
    val metadataFetchingService = get<MetadataFetchingService>()
    val musicBrainzWorker = get<MusicBrainzWorker>()
    val autoTranscodeWorker = get<AutoTranscodeWorker>()

    scheduleService.schedule(
        ScheduledTask(
            name = "Database Backup",
            trigger = CronPresets.dailyAt(2, 0),
            task = {
                logTask("Database Backup") {
                    val res = backupService.createBackup()
                    mapOf("fileName" to res.fileName, "size" to res.size, "imageCount" to res.imageCount)
                }
            }
        )
    )

    scheduleService.schedule(
        ScheduledTask(
            name = "User Playlist Backup",
            trigger = CronPresets.dailyAt(2, 0),
            task = {
                logTask("User Playlist Backup") {
                    val count = userPlaylistBackupService.backupAllUsers()
                    mapOf("userCount" to count)
                }
            }
        )
    )

    scheduleService.schedule(
        ScheduledTask(
            name = "Session Cleanup",
            trigger = CronPresets.dailyAt(0, 0),
            task = {
                logTask("Session Cleanup") {
                    val count = sessionService.cleanupOldSessions()
                    mapOf("sessionsDeleted" to count)
                }
            }
        )
    )

    val mergeDuplicates = scheduleService.schedule(
        ScheduledTask(
            name = "Merge Library Duplicates",
            trigger = CronPresets.dailyAt(1, 0),
            task = {
                logTask("Merge Library Duplicates") {
                    libraryMergeService.mergeDuplicates()
                }
            }
        )
    )

    scheduleService.triggerTask(mergeDuplicates.id)

    scheduleService.schedule(
        ScheduledTask(
            name = "Fetch Artist Images (Tidal)",
            trigger = CronPresets.dailyAt(4, 0),
            task = {
                logTask("Fetch Artist Images (Tidal)") {
                    metadataFetchingService.fetchArtistImages(MetadataService.Companion.MetadataType.tidal) {
                        log.info(it)
                    }
                }
            }
        )
    )

    scheduleService.schedule(
        ScheduledTask(
            name = "Auto Transcoding",
            trigger = CronPresets.dailyAt(3, 0),
            task = {
                logTask("Auto Transcoding") {
                    autoTranscodeWorker.run()
                }
            }
        )
    )

    val musicBrainzTask = scheduleService.schedule(
        ScheduledTask(
            name = "MusicBrainz Worker",
            trigger = CronPresets.dailyAt(0, 0),
            task = {
                logTask("MusicBrainz Worker") {
                    musicBrainzWorker.run()
                }
            }
        )
    )

    scheduleService.triggerTask(musicBrainzTask.id)

    val cleanAlbumTask = scheduleService.schedule(
        ScheduledTask(
            name = "Delete Empty Albums",
            trigger = CronPresets.dailyAt(0, 0),
            task = {
                logTask("Delete Empty Albums") {
                    val count = albumService.deleteEmptyAlbums()
                    mapOf("albumsDeleted" to count)
                }
            }
        )
    )

    val cleanArtistsTask = scheduleService.schedule(
        ScheduledTask(
            name = "Delete Unreferenced Artists",
            trigger = TaskCompletionTrigger(cleanAlbumTask.id),
            task = {
                logTask("Delete Unreferenced Artists") {
                    val count = artistService.deleteUnreferencedArtists()
                    mapOf("artistsDeleted" to count)
                }
            }
        )
    )

    scheduleService.schedule(
        ScheduledTask(
            name = "Delete Unreferenced Images",
            trigger = TaskCompletionTrigger(cleanArtistsTask.id),
            task = {
                logTask("Delete Unreferenced Images") {
                    val count = imageService.deleteUnreferencedImages()
                    mapOf("imagesDeleted" to count)
                }
            }
        )
    )

    CoroutineScope(Dispatchers.IO).launch {
        launch { scheduleService.startService() }
        launch { reverseProxyService.startService() }
    }

    configureHTTP()
    configureRouting()
    configureDatabases()
}
