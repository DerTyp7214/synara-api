package dev.dertyp.services.cover

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val coverModule = module {
    singleOf(::CoverAssetPackService)
    singleOf(::CoverSourceCollector)
    singleOf(::CoverGenerationService)
    singleOf(::CoverAutoTrigger)
}
