package dev.dertyp

import dev.dertyp.plugins.JmDNSPlugin
import dev.dertyp.services.*
import dev.dertyp.services.tdn.DownloadService
import dev.dertyp.services.tdn.TdnService
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    install(CallLogging)
    install(JmDNSPlugin) {
        serviceName = "synara-api"
        serviceType = "_synara-api._tcp.local."
    }

    val databaseManager = DatabaseManager(environment)

    install(Koin) {
        slf4jLogger()
        modules(module {
            single<ApplicationEnvironment> { environment }
            single<DatabaseManager> { databaseManager }
            singleOf(::StorageService)
            singleOf(::UserService)
            singleOf(::RefreshTokenService)
            singleOf(::JwtService)
            singleOf(::SongService)
            singleOf(::ImageService)
            singleOf(::AlbumService)
            singleOf(::ArtistService)
            singleOf(::PlaylistService)
            singleOf(::Indexer)
            singleOf(::TdnService)
            singleOf(::DownloadService)
        })
    }

    configureHTTP()
    configureRouting()
    configureDatabases()
}