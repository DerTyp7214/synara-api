package dev.dertyp.core

import dev.dertyp.data.User
import dev.dertyp.services.UserService
import dev.dertyp.services.metadata.MetadataService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.koin.ktor.ext.get
import org.koin.ktor.ext.inject

fun ApplicationCall.getUsername(): String = principal<JWTPrincipal>()?.get("username")!!
suspend fun ApplicationCall.getUser(): User? = get<UserService>().findUserByUsername(getUsername())

fun ApplicationCall.getMetadataProvider(providerType: MetadataService.Companion.MetadataType? = null): MetadataService? {
    val environment by inject<ApplicationEnvironment>()

    val metadataProvider = if (providerType != null) providerType else {
        val metadataProviderString = this.parameters["metadataProvider"] ?: return null
        MetadataService.Companion.MetadataType.valueOf(metadataProviderString)
    }

    return MetadataService.getMetadataService(metadataProvider, environment)
}