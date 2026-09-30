package dev.dertyp.services

import dev.dertyp.core.db.SqliteForeignKeyCheck
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.plugins.PluginManager
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val systemModule = module {
    singleOf(::HookService) { bind<HookBus>() }
    singleOf(::PluginManager)
    singleOf(::StorageService) { bind<IServerStorageService>() }
    singleOf(::DatabaseManager)
    singleOf(::SqliteForeignKeyCheck)
    singleOf(::DbManagementService)
    singleOf(::CustomMigrationService)
    singleOf(::BackupService)
    singleOf(::UserPlaylistBackupService)
    singleOf(::MirrorService)
    singleOf(::RemoteMirrorService)
    singleOf(::ReverseProxyService)
    singleOf(::ServerStatsService)
    singleOf(::RpcMetricsCollector)
    singleOf(::RpcMetricsService)
}
