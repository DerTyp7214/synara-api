package dev.dertyp.services.hue

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val hueModule = module {
    singleOf(::HueDiscoveryService)
    singleOf(::HueService)
}
