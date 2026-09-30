package dev.dertyp.services

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val authModule = module {
    singleOf(::ApiKeyService)
    singleOf(::ApiKeyScopeRegistry)
    singleOf(::JwtService)
    singleOf(::UserService)
    singleOf(::AuthService)
    singleOf(::RefreshTokenService)
    singleOf(::SessionService)
}
