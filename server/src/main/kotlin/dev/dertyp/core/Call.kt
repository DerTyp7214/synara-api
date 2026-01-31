package dev.dertyp.core

import dev.dertyp.data.User
import dev.dertyp.services.SessionService
import dev.dertyp.services.UserService
import dev.dertyp.services.metadata.MetadataService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.ktor.ext.get
import org.koin.ktor.ext.inject
import java.util.*

fun ApplicationCall.getUsername(): String = principal<JWTPrincipal>()?.get("usr")!!
fun ApplicationCall.getSessionId(): UUID? = principal<JWTPrincipal>()?.get("ses")?.let { UUID.fromString(it) }

suspend fun ApplicationCall.getUser(): User? {
    val user = get<UserService>().findUserByUsername(getUsername())
    val sessionId = getSessionId()

    if (user != null && sessionId != null) {
        val sessionService = get<SessionService>()
        CoroutineScope(Dispatchers.IO).launch {
            sessionService.updateSessionActivity(sessionId, user.id)
        }
    }

    return user
}

fun ApplicationCall.getMetadataProvider(providerType: MetadataService.Companion.MetadataType? = null): MetadataService? {
    val environment by inject<ApplicationEnvironment>()

    val metadataProvider = if (providerType != null) providerType else {
        val metadataProviderString = this.parameters["metadataProvider"] ?: return null
        MetadataService.Companion.MetadataType.valueOf(metadataProviderString)
    }

    return MetadataService.getMetadataService(metadataProvider, environment)
}