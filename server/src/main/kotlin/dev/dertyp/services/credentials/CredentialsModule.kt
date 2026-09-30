package dev.dertyp.services.credentials

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val credentialsModule = module {
    singleOf(::CredentialCipher)
}
