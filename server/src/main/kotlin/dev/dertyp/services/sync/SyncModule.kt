package dev.dertyp.services.sync

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val syncModule = module {
    singleOf(::ListenBrainzService)
    singleOf(::ListenBackupService)
}
