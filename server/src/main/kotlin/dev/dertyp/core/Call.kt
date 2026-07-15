package dev.dertyp.core

import dev.dertyp.data.User
import dev.dertyp.services.ApiKeyService
import dev.dertyp.services.SessionService
import dev.dertyp.services.UserService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataService
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.auth.principal
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.ktor.ext.get
import org.koin.ktor.ext.inject
import java.util.UUID

val ProxiedKey = AttributeKey<Boolean>("Proxied")
val ApplicationCall.isProxied: Boolean get() = attributes.getOrNull(ProxiedKey) ?: false
val ApplicationCall.principalUsername: String? get() = principal<JWTPrincipal>()?.get("usr")

fun ApplicationCall.getUsername(): String = principalUsername!!
fun ApplicationCall.getSessionId(): UUID? = principal<JWTPrincipal>()?.get("ses")?.let { UUID.fromString(it) }

suspend fun ApplicationCall.getUser(): User? = try {
    val user = get<UserService>().findUserByUsername(getUsername())
    val sessionId = getSessionId()

    if (user != null && sessionId != null) {
        val sessionService = get<SessionService>()
        CoroutineScope(Dispatchers.IO).launch {
            sessionService.updateSessionActivity(sessionId, user.id)
        }
    }

    user
} catch (_: Throwable) {
    null
}

suspend fun ApplicationCall.apiKeyUser(): User? {
    val bearer = (request.parseAuthorizationHeader() as? HttpAuthHeader.Single)
        ?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }
        ?.blob
    val raw = request.queryParameters["apiKey"]
        ?: request.headers["X-API-Key"]
        ?: bearer
        ?: return null
    return get<ApiKeyService>().resolveUser(raw)
}

fun ApplicationCall.getMetadataProvider(providerType: IMetadataService.MetadataType? = null): MetadataService? {
    val environment by inject<ApplicationEnvironment>()

    val metadataProvider = if (providerType != null) providerType else {
        val metadataProviderString = this.parameters["metadataProvider"] ?: return null
        IMetadataService.MetadataType(metadataProviderString)
    }

    return MetadataService.getMetadataService(metadataProvider, environment)
}