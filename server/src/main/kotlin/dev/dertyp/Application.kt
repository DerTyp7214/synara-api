package dev.dertyp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import dev.dertyp.core.logTask
import dev.dertyp.data.RemoteServerConfig
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserTable
import dev.dertyp.plugins.*
import dev.dertyp.serializers.ByteArrayISO8859TypeAdapter
import dev.dertyp.serializers.DurationAdapter
import dev.dertyp.serializers.LocalDateAdapter
import dev.dertyp.serializers.OffsetDateTimeAdapter
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.*
import dev.dertyp.services.download.DownloadService
import dev.dertyp.services.download.DownloaderProxy
import dev.dertyp.services.metadata.*
import dev.dertyp.services.schedule.*
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.bridge.SLF4JBridgeHandler
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

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
            singleOf(::PluginManager)
            singleOf(::JwtService)
            singleOf(::UserService)
            singleOf(::AuthService)
            singleOf(::SongService)
            singleOf(::AudioAnalysisService)
            singleOf(::FlacAnalysisService)
            singleOf(::ImageService)
            singleOf(::AlbumService)
            singleOf(::LyricsSearch)
            singleOf(::LyricsService)
            singleOf(::LrcLibService)
            singleOf(::GenreService)
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
            singleOf(::DiscoveryService)
            singleOf(::DownloaderProxy)
            singleOf(::SessionService)
            singleOf(::PlaybackService)
            singleOf(::CustomAudioService)
            singleOf(::ReverseProxyService)
            singleOf(::DbManagementService)
            singleOf(::BackupService)
            singleOf(::UserPlaylistBackupService)
            singleOf(::MetadataFetchingService)
            singleOf(::MetadataDispatcherService)
            singleOf(::MirrorService)
            singleOf(::RemoteMirrorService)
            singleOf(::MusicBrainzService)
            singleOf(::MusicBrainzCacheService)
            singleOf(::CachedMusicBrainzService)
            singleOf(::MusicBrainzWorker)
            singleOf(::MusicBrainzCacheWorker)
            singleOf(::GenreMetadataWorker)
            singleOf(::RecentReleaseWorker)
            singleOf(::LyricsSyncWorker)
            singleOf(::LrcLibWorker)
            singleOf(::AudioAnalysisWorker)
            singleOf(::FlacAnalysisWorker)
            singleOf(::ReleaseService)
            singleOf(::AutoTranscodeWorker)
            singleOf(::CustomMigrationService)

            single<IMusicBrainzService> { get<CachedMusicBrainzService>() }

            single<Gson> {
                GsonBuilder()
                    .registerTypeAdapter(OffsetDateTime::class.java, OffsetDateTimeAdapter())
                    .registerTypeAdapter(ByteArray::class.java, ByteArrayISO8859TypeAdapter())
                    .registerTypeAdapter(LocalDate::class.java, LocalDateAdapter())
                    .registerTypeAdapter(Duration::class.java, DurationAdapter())
                    .registerTypeHierarchyAdapter(Flow::class.java, object : TypeAdapter<Flow<*>>() {
                        override fun write(out: JsonWriter, value: Flow<*>?) {
                            out.nullValue()
                        }

                        override fun read(reader: JsonReader): Flow<*> {
                            if (reader.peek() == JsonToken.NULL) {
                                reader.nextNull()
                            } else {
                                reader.skipValue()
                            }
                            return emptyFlow<Any>()
                        }
                    })
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
        }, pluginModule)
    }

    get<DatabaseManager>().init()

    val backupService = get<BackupService>()
    val remoteMirrorService = get<RemoteMirrorService>()
    val setupFromBackup = environment.config.propertyOrNull("setup.fromBackup")?.getString()
    val setupFromMirrorUrl = environment.config.propertyOrNull("setup.fromMirror.url")?.getString()
    
    if (!setupFromBackup.isNullOrBlank() || !setupFromMirrorUrl.isNullOrBlank()) {
        transaction {
            val songCount = SongTable.selectAll().count()
            val userCount = UserTable.selectAll().count()
            if ((songCount == 0L) && (userCount <= 1L)) {
                if (!setupFromBackup.isNullOrBlank()) {
                    log.info("Database is empty. Setting up from backup: $setupFromBackup")
                    val backupFile = File(setupFromBackup)
                    if (backupFile.exists()) {
                        runBlocking {
                            backupService.loadBackup(backupFile)
                        }
                        log.info("Backup restored successfully. Restarting server...")
                        exitProcess(0)
                    } else {
                        log.error("Backup file not found: $setupFromBackup")
                    }
                } else if (!setupFromMirrorUrl.isNullOrBlank()) {
                    val setupFromMirrorUser = environment.config.propertyOrNull("setup.fromMirror.username")?.getString()
                    val setupFromMirrorPass = environment.config.propertyOrNull("setup.fromMirror.password")?.getString()
                    
                    if (setupFromMirrorUser != null && setupFromMirrorPass != null) {
                        log.info("Database is empty. Setting up from mirror: $setupFromMirrorUrl")
                        val url = setupFromMirrorUrl.substringAfter("://")
                        val host = url.substringBefore(":")
                        val port = url.substringAfter(":", "8080").substringBefore("/").toIntOrNull() ?: 8080
                        val secure = setupFromMirrorUrl.startsWith("https")
                        
                        runBlocking {
                            remoteMirrorService.startMirror(RemoteServerConfig(
                                host = host,
                                port = port,
                                username = setupFromMirrorUser,
                                password = setupFromMirrorPass,
                                secure = secure,
                                isImport = true,
                                importUsers = true
                            ))

                            while (remoteMirrorService.isMirroring) {
                                delay(1.seconds)
                            }
                        }
                        log.info("Mirror setup completed. Restarting server...")
                        exitProcess(0)
                    } else {
                        log.error("Mirror setup requested but username or password missing")
                    }
                }
            }
        }
    }

    val logService = get<ScheduledTaskLogService>()
    val customMigrationService = get<CustomMigrationService>()
    CoroutineScope(Dispatchers.IO).launch {
        logService.cleanupRunningLogs()
        customMigrationService.runMigrations()
    }

    val scheduleService = get<ScheduleService>()
    val sessionService = get<SessionService>()
    val imageService = get<ImageService>()
    val libraryMergeService = get<LibraryMergeService>()
    val artistService = get<ArtistService>()
    val albumService = get<AlbumService>()
    val userPlaylistBackupService = get<UserPlaylistBackupService>()
    val reverseProxyService = get<ReverseProxyService>()
    val metadataFetchingService = get<MetadataFetchingService>()
    val musicBrainzWorker = get<MusicBrainzWorker>()
    val musicBrainzCacheWorker = get<MusicBrainzCacheWorker>()
    val genreMetadataWorker = get<GenreMetadataWorker>()
    val recentReleaseWorker = get<RecentReleaseWorker>()
    val autoTranscodeWorker = get<AutoTranscodeWorker>()
    val lyricsSyncWorker = get<LyricsSyncWorker>()
    val audioAnalysisWorker = get<AudioAnalysisWorker>()
    val flacAnalysisWorker = get<FlacAnalysisWorker>()
    val lrcLibWorker = get<LrcLibWorker>()

    scheduleService.schedule(
        ScheduledTask(
            name = "Database Backup",
            trigger = CronPresets.dailyAt(2, 0),
            task = {
                scheduleService.logTask("Database Backup") {
                    val res = backupService.createBackup { p, l -> updateProgress(p, l) }
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
                scheduleService.logTask("User Playlist Backup") {
                    val count = userPlaylistBackupService.backupAllUsers { p, l -> updateProgress(p, l) }
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
                scheduleService.logTask("Session Cleanup") {
                    val count = sessionService.cleanupOldSessions { p, l -> updateProgress(p, l) }
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
                scheduleService.logTask("Merge Library Duplicates") {
                    libraryMergeService.mergeDuplicates { p, l -> updateProgress(p, l) }
                }
            }
        )
    )

    scheduleService.triggerTask(mergeDuplicates.id)

    val audioAnalysis = scheduleService.schedule(
        ScheduledTask(
            name = "Audio Analysis",
            trigger = CronPresets.dailyAt(3, 0),
            task = {
                scheduleService.logTask("Audio Analysis") {
                    audioAnalysisWorker.run { p, l -> updateProgress(p, l) }
                }
            }
        )
    )

    scheduleService.triggerTask(audioAnalysis.id)

    val flacAnalysis = scheduleService.schedule(
        ScheduledTask(
            name = "FLAC Analysis",
            trigger = CronPresets.dailyAt(5, 0),
            task = {
                scheduleService.logTask("FLAC Analysis") {
                    flacAnalysisWorker.run { p, l -> updateProgress(p, l) }
                }
            }
        )
    )

    scheduleService.triggerTask(flacAnalysis.id)

    val musicBrainzTask = scheduleService.schedule(
        ScheduledTask(
            name = "MusicBrainz Worker",
            trigger = CronPresets.dailyAt(0, 0),
            task = {
                scheduleService.logTask("MusicBrainz Worker") {
                    musicBrainzWorker.run { p, l -> updateProgress(p, l) }
                }
            }
        )
    )

    scheduleService.triggerTask(musicBrainzTask.id)

    val musicBrainzCacheTask = scheduleService.schedule(
        ScheduledTask(
            name = "MusicBrainz Cache Worker",
            trigger = TaskCompletionTrigger(musicBrainzTask.id),
            task = {
                scheduleService.logTask("MusicBrainz Cache Worker") {
                    musicBrainzCacheWorker.run { p, l -> updateProgress(p, l) }
                }
            }
        )
    )

    scheduleService.triggerTask(musicBrainzCacheTask.id)

    val genreMetadataTask = scheduleService.schedule(
        ScheduledTask(
            name = "Genre Metadata Worker",
            trigger = TaskCompletionTrigger(musicBrainzTask.id),
            task = {
                scheduleService.logTask("Genre Metadata Worker") {
                    genreMetadataWorker.run { p, l -> updateProgress(p, l) }
                }
            }
        )
    )

    val fetchArtistImagesTidal = scheduleService.schedule(
        ScheduledTask(
            name = "Fetch Artist Images (Tidal)",
            trigger = TaskCompletionTrigger(genreMetadataTask.id),
            task = {
                scheduleService.logTask("Fetch Artist Images (Tidal)") {
                    metadataFetchingService.fetchArtistImages(IMetadataService.MetadataType.tidal) { p, l ->
                        updateProgress(p, l)
                    }
                }
            }
        )
    )

    scheduleService.schedule(
        ScheduledTask(
            name = "Fetch Metadata (TheAudioDB)",
            trigger = TaskCompletionTrigger(fetchArtistImagesTidal.id),
            task = {
                scheduleService.logTask("Fetch Metadata (TheAudioDB)") {
                    metadataFetchingService.fetchMetadata(IMetadataService.MetadataType.theAudioDB) { p, l ->
                        updateProgress(p, l)
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
                scheduleService.logTask("Auto Transcoding") {
                    autoTranscodeWorker.run { p, l -> updateProgress(p, l) }
                }
            }
        )
    )

    val lyricsSyncTask = scheduleService.schedule(
        ScheduledTask(
            name = "Lyrics Sync Worker",
            trigger = CronPresets.dailyAt(4, 0),
            task = {
                scheduleService.logTask("Lyrics Sync Worker") {
                    lyricsSyncWorker.run { p, l -> updateProgress(p, l) }
                }
            }
        )
    )

    scheduleService.triggerTask(lyricsSyncTask.id)

    val lrcLibTask = scheduleService.schedule(
        ScheduledTask(
            name = "LrcLib Worker",
            trigger = CronPresets.dailyAt(4, 30),
            task = {
                scheduleService.logTask("LrcLib Worker") {
                    lrcLibWorker.run { p, l -> updateProgress(p, l) }
                }
            }
        )
    )

    scheduleService.triggerTask(lrcLibTask.id)

    val recentReleaseTask = scheduleService.schedule(
        ScheduledTask(
            name = "Recent Release Worker",
            trigger = CronPresets.dailyAt(1, 0),
            task = {
                scheduleService.logTask("Recent Release Worker") {
                    recentReleaseWorker.run { p, l -> updateProgress(p, l) }
                }
            }
        )
    )

    scheduleService.triggerTask(recentReleaseTask.id)

    val cleanAlbumTask = scheduleService.schedule(
        ScheduledTask(
            name = "Delete Empty Albums",
            trigger = CronPresets.dailyAt(0, 0),
            task = {
                scheduleService.logTask("Delete Empty Albums") {
                    val count = albumService.deleteEmptyAlbums { p, l -> updateProgress(p, l) }
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
                scheduleService.logTask("Delete Unreferenced Artists") {
                    val count = artistService.deleteUnreferencedArtists { p, l -> updateProgress(p, l) }
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
                scheduleService.logTask("Delete Unreferenced Images") {
                    val count = imageService.deleteUnreferencedImages { p, l ->
                        updateProgress(p, l)
                    }
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
    configureServices()
}
