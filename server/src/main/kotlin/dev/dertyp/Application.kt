package dev.dertyp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import dev.dertyp.core.configureScheduledTasks
import dev.dertyp.data.RemoteServerConfig
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserTable
import dev.dertyp.plugins.JmDNSPlugin
import dev.dertyp.plugins.PluginManager
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.plugins.pluginModule
import dev.dertyp.serializers.ByteArrayISO8859TypeAdapter
import dev.dertyp.serializers.DurationAdapter
import dev.dertyp.serializers.LocalDateAdapter
import dev.dertyp.serializers.OffsetDateTimeAdapter
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.*
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.import.ImporterProxy
import dev.dertyp.services.metadata.*
import dev.dertyp.services.schedule.ScheduleService
import dev.dertyp.services.schedule.ScheduledTaskConfigurationService
import dev.dertyp.services.sync.ListenBrainzService
import dev.dertyp.services.schedule.Worker
import dev.dertyp.services.schedule.WorkerTask
import io.github.classgraph.ClassGraph
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.binds
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
        modules(mainModule(application, environment), pluginModule)
    }

    get<DatabaseManager>().init()
    get<RedisSearchService>().initIndex()

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
    val configService = get<ScheduledTaskConfigurationService>()
    
    runBlocking {
        configService.ensureDefaults(ScheduledTaskConfigurationService.DEFAULTS)
    }

    configureScheduledTasks()

    CoroutineScope(Dispatchers.IO).launch {
        launch { scheduleService.startService() }
    }

    val linkResolverService = get<LinkResolverService>()
    CoroutineScope(Dispatchers.IO).launch {
        linkResolverService.refreshSupported()
    }

    val metricsCollector = get<RpcMetricsCollector>()
    if (metricsCollector.enabled) {
        CoroutineScope(Dispatchers.IO).launch {
            metricsCollector.runFlushLoop()
        }
    }

    configureHTTP()
    configureRouting()
    configureServices()
}

fun mainModule(application: Application, environment: ApplicationEnvironment): Module = module {
    single<Application> { application }
    single<ApplicationEnvironment> { environment }
    single { environment.config }

    singleOf(::Indexer)
    singleOf(::HookService)
    singleOf(::ListenService)
    singleOf(::ListenBrainzService)
    singleOf(::AudioEmbeddingService)
    singleOf(::RecommendationService)
    singleOf(::RecommendationServingService)
    singleOf(::PluginManager)
    singleOf(::JwtService)
    singleOf(::UserService)
    singleOf(::AuthService)
    singleOf(::SongService)
    singleOf(::AudioAnalysisService)
    singleOf(::FlacAnalysisService)
    singleOf(::ImageService)
    singleOf(::AnimatedImageService)
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
    singleOf(::ImportService)
    singleOf(::ScheduleService)
    singleOf(::ScheduledTaskConfigurationService)
    singleOf(::ServerStatsService)
    singleOf(ApplicationConfig::toMetricsConfig)
    singleOf(::RpcMetricsCollector)
    singleOf(::RpcMetricsService)
    singleOf(::UserPlaylistService)
    singleOf(::CollectionService)
    singleOf(::RefreshTokenService)
    singleOf(::ScheduledTaskLogService)
    singleOf(::DiscoveryService)
    singleOf(::ImporterProxy)
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
    singleOf(::LinkResolverService)
    singleOf(::ReleaseService)
    singleOf(::SearchIndexWorker)
    singleOf(::RedisSearchService)

    ClassGraph()
        .enableClassInfo()
        .enableAnnotationInfo()
        .acceptPackages("dev.dertyp.services.schedule")
        .scan().use { scanResult ->
            scanResult.getClassesWithAnnotation(WorkerTask::class.java.name).forEach { classInfo ->
                val clazz = classInfo.loadClass()
                single { clazz.getDeclaredConstructor().newInstance() } binds arrayOf(clazz.kotlin, Worker::class)
            }
        }
    
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
                useRedisSearch = environment.config.propertyOrNull("redis.useSearch")?.getString()?.toBoolean() ?: useRedisSearch
                indexPrefix = environment.config.propertyOrNull("redis.indexPrefix")?.getString() ?: indexPrefix
                cacheAnimatedImages = environment.config.propertyOrNull("redis.cacheAnimatedImages")?.getString()?.toBoolean() ?: cacheAnimatedImages
            }
        } else RedisCacheProvider.Config().apply { host = "none" }
    }
}
