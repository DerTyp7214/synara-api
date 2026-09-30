package dev.dertyp.services.subsonic

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val subsonicModule = module {
    singleOf(::SubsonicCredentialService)
}
