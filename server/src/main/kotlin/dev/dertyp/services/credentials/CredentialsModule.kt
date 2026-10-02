package dev.dertyp.services.credentials

import dev.dertyp.services.credentials.admin.CredentialServerAdminClient
import dev.dertyp.services.credentials.remote.CredentialServerClient
import dev.dertyp.services.credentials.remote.RemoteCredentialProvider
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val credentialsModule = module {
    singleOf(::CredentialCipher)
    singleOf(::CredentialServerConnectionSource)
    singleOf(::ClientCredentialsExchange)
    singleOf(::AppleDeveloperTokenSigner)
    singleOf(::LocalPluginCredentialStore)
    singleOf(::LocalCredentialStore)
    singleOf(::LocalCredentialProvider)
    singleOf(::CredentialServerClient)
    singleOf(::RemoteCredentialProvider)
    singleOf(::RoutingCredentialProvider) { bind<CredentialProvider>() }
    singleOf(::ImporterCredentialMaterializer)
    singleOf(::PluginCredentialsFactory)
    singleOf(::CredentialServerAdminClient)
}
