package dev.dertyp

import dev.dertyp.config.ServerConfig
import dev.dertyp.config.configModule
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientFactory
import dev.dertyp.core.HttpClientQueueService
import dev.dertyp.core.configureScheduledTasks
import dev.dertyp.core.coreModule
import dev.dertyp.core.db.SqliteForeignKeyCheck
import dev.dertyp.core.process.killAll
import dev.dertyp.data.RemoteServerConfig
import dev.dertyp.db.BackupSchemaException
import dev.dertyp.db.DatabaseNotAtBaseException
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserTable
import dev.dertyp.mcp.mcpModule
import dev.dertyp.plugins.JmDNSPlugin
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.*
import dev.dertyp.services.cover.coverModule
import dev.dertyp.services.credentials.credentialsModule
import dev.dertyp.services.hue.hueModule
import dev.dertyp.services.import.importModule
import dev.dertyp.services.intake.ImporterResolvers
import dev.dertyp.services.intake.IntakeService
import dev.dertyp.services.intake.intakeModule
import dev.dertyp.services.metadata.LinkResolverService
import dev.dertyp.services.metadata.metadataModule
import dev.dertyp.services.podcast.podcastModule
import dev.dertyp.services.release.releaseModule
import dev.dertyp.services.schedule.ScheduleService
import dev.dertyp.services.schedule.ScheduledTaskConfigurationService
import dev.dertyp.services.schedule.scheduleModule
import dev.dertyp.services.subsonic.subsonicModule
import dev.dertyp.services.sync.syncModule
import dev.dertyp.services.ui.CoreUiContributions
import dev.dertyp.services.ui.uiModule
import io.ktor.http.DEFAULT_PORT
import io.ktor.http.URLProtocol
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.jaudiotagger.audio.wav.WavOptions
import org.jaudiotagger.audio.wav.WavSaveOptions
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.reference.ID3V2Version
import org.slf4j.bridge.SLF4JBridgeHandler
import java.io.File
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun configureTagOptions() {
    TagOptionSingleton.getInstance().apply {
        iD3V2Version = ID3V2Version.ID3_V24
        wavOptions = WavOptions.READ_ID3_UNLESS_ONLY_INFO
        wavSaveOptions = WavSaveOptions.SAVE_BOTH
    }
}

fun Application.module() {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
    configureTagOptions()

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
        modules(mainModule(application, environment))
    }
    configureHooks()

    ServiceLifecycle.register(get<DatabaseManager>())
    ServiceLifecycle.start(get<HttpClientFactory>())
    ServiceLifecycle.start(get<HttpClientQueueService>())

    try {
        get<DatabaseManager>().init()
    } catch (refusal: DatabaseNotAtBaseException) {
        log.error(refusal.message)
        Runtime.getRuntime().halt(1)
    }
    configureCache()

    val backupService = get<BackupService>()
    val remoteMirrorService = get<RemoteMirrorService>()
    val setup = get<ServerConfig>().setup
    val setupFromBackup = setup.fromBackup
    val setupFromMirrorUrl = setup.fromMirror.url

    if (!setupFromBackup.isNullOrBlank() || !setupFromMirrorUrl.isNullOrBlank()) {
        val databaseIsEmpty = transaction {
            SongTable.selectAll().count() == 0L && UserTable.selectAll().count() <= 1L
        }
        if (databaseIsEmpty) {
            if (!setupFromBackup.isNullOrBlank()) {
                log.info("Database is empty. Setting up from backup: $setupFromBackup")
                val backupFile = File(setupFromBackup)
                if (backupFile.exists()) {
                    try {
                        runBlocking {
                            backupService.loadBackup(backupFile)
                        }
                    } catch (refusal: BackupSchemaException) {
                        log.error(refusal.message)
                        Runtime.getRuntime().halt(1)
                    }
                    log.info("Backup restored successfully. Restarting server...")
                    Runtime.getRuntime().halt(0)
                } else {
                    log.error("Backup file not found: $setupFromBackup")
                }
            } else if (!setupFromMirrorUrl.isNullOrBlank()) {
                val setupFromMirrorUser = setup.fromMirror.username
                val setupFromMirrorPass = setup.fromMirror.password
                val endpoint = setup.fromMirror.endpoint

                if (setupFromMirrorUser != null && setupFromMirrorPass != null && endpoint != null) {
                    log.info("Database is empty. Setting up from mirror: $setupFromMirrorUrl")
                    val port = endpoint.specifiedPort.takeIf { it != DEFAULT_PORT } ?: 8080
                    val secure = endpoint.protocol == URLProtocol.HTTPS

                    runBlocking {
                        remoteMirrorService.startMirror(
                            RemoteServerConfig(
                                host = endpoint.host,
                                port = port,
                                username = setupFromMirrorUser,
                                password = setupFromMirrorPass,
                                secure = secure,
                                isImport = true,
                                importUsers = true
                            )
                        )

                        while (remoteMirrorService.isMirroring) {
                            delay(1.seconds)
                        }
                    }
                    log.info("Mirror setup completed. Restarting server...")
                    Runtime.getRuntime().halt(0)
                } else {
                    log.error("Mirror setup requested but username or password missing")
                }
            }
        }
    }

    val logService = get<ScheduledTaskLogService>()
    val customMigrationService = get<CustomMigrationService>()
    val foreignKeyCheck = get<SqliteForeignKeyCheck>()
    ApplicationScope.scope.launch(Dispatchers.IO) {
        logService.cleanupRunningLogs()
        customMigrationService.runMigrations()
        foreignKeyCheck.run()
    }

    val scheduleService = get<ScheduleService>()
    val configService = get<ScheduledTaskConfigurationService>()

    runBlocking {
        configService.ensureDefaults(ScheduledTaskConfigurationService.DEFAULTS)
    }

    configureScheduledTasks()

    ServiceLifecycle.start(scheduleService)

    val linkResolverService = get<LinkResolverService>()
    ApplicationScope.scope.launch(Dispatchers.IO) {
        linkResolverService.refreshSupported()
    }

    val metricsCollector = get<RpcMetricsCollector>()
    if (metricsCollector.enabled) {
        ApplicationScope.scope.launch(Dispatchers.IO) {
            metricsCollector.runFlushLoop()
        }
    }

    configureHTTP()
    configureRouting()
    get<CoreUiContributions>().register()
    get<ImporterResolvers>().register(get<IntakeService>())
    configureServices()
    configureShutdown()
}

fun Application.configureShutdown() {
    monitor.subscribe(ApplicationStopping) {
        try {
            runBlocking { ServiceLifecycle.stopAll() }
        } catch (e: Exception) {
            log.error("Failed to stop services", e)
        }
        try {
            ApplicationScope.scope.cancel()
        } catch (e: Exception) {
            log.error("Failed to cancel application scope", e)
        }
        try {
            killAll()
        } catch (e: Exception) {
            log.error("Failed to kill child processes", e)
        }
    }
}

fun mainModule(application: Application, environment: ApplicationEnvironment): Module = module {
    includes(
        coreModule(application, environment),
        configModule,
        systemModule,
        authModule,
        libraryModule,
        listeningModule,
        audioModule,
        credentialsModule,
        metadataModule,
        releaseModule,
        importModule,
        intakeModule,
        podcastModule,
        coverModule,
        uiModule,
        scheduleModule,
        syncModule,
        hueModule,
        subsonicModule,
        mcpModule,
    )
}
