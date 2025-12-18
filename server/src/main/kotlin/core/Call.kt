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

fun ApplicationCall.getMetadataProvider(): MetadataService? {
    val environment by inject<ApplicationEnvironment>()

    val metadataProviderString = this.parameters["metadataProvider"] ?: return null
    val metadataProvider = MetadataService.Companion.MetadataType.valueOf(metadataProviderString)

    return MetadataService.getMetadataService(metadataProvider, environment)
}