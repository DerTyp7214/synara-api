package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.TidalLoginEvent
import dev.dertyp.credentials.TidalLoginSession
import dev.dertyp.credentials.TidalLoginStart
import dev.dertyp.credentials.TidalLoginState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TidalDeviceLoginManager(
    private val repository: SecretRepository,
    httpClient: HttpClient,
    private val scope: CoroutineScope,
) {
    private class Login(val events: MutableStateFlow<TidalLoginEvent>, val job: Job)

    private data class LoginTarget(
        val name: String,
        val start: TidalLoginStart,
        val clientId: String,
        val clientSecret: String,
        val authorization: TidalDeviceAuthorization,
    )

    private val authApi = TidalAuthApi(httpClient)
    private val logins = ConcurrentHashMap<String, Login>()

    suspend fun start(name: String, request: TidalLoginStart): TidalLoginSession {
        if (!CredentialNames.isValid(name)) {
            throw CredentialException(CredentialErrorCode.INVALID, "Invalid credential name $name")
        }
        val previous = when (val existing = repository.loadSecret(name)) {
            null -> null
            is TidalSessionSecret -> existing
            else -> throw CredentialException(CredentialErrorCode.INVALID, "$name is not a Tidal session credential")
        }
        val clientId = request.clientId?.takeIf { it.isNotBlank() } ?: previous?.clientId
            ?: throw CredentialException(CredentialErrorCode.INVALID, "A Tidal client id is required")
        val clientSecret = request.clientSecret?.takeIf { it.isNotBlank() } ?: previous?.clientSecret
            ?: throw CredentialException(CredentialErrorCode.INVALID, "A Tidal client secret is required")
        val authorization = authApi.deviceAuthorization(clientId)
        val loginId = UUID.randomUUID().toString()
        val events = MutableStateFlow(TidalLoginEvent(TidalLoginState.PENDING))
        val target = LoginTarget(name, request, clientId, clientSecret, authorization)
        val job = scope.launch {
            events.finish(poll(target))
        }
        job.invokeOnCompletion {
            events.finish(CANCELLED)
            scope.launch {
                delay(RETENTION)
                logins.remove(loginId)
            }
        }
        logins[loginId] = Login(events, job)
        return TidalLoginSession(
            loginId = loginId,
            verificationUri = authorization.verificationUri,
            verificationUriComplete = authorization.verificationUriComplete,
            userCode = authorization.userCode,
            expiresAt = System.currentTimeMillis() + authorization.expiresInSeconds * 1000,
        )
    }

    fun events(loginId: String): StateFlow<TidalLoginEvent>? = logins[loginId]?.events?.asStateFlow()

    fun cancel(loginId: String) {
        val login = logins[loginId] ?: return
        login.events.finish(CANCELLED)
        login.job.cancel()
    }

    private fun MutableStateFlow<TidalLoginEvent>.finish(event: TidalLoginEvent) {
        update { if (it.state == TidalLoginState.PENDING) event else it }
    }

    private suspend fun poll(target: LoginTarget): TidalLoginEvent {
        val authorization = target.authorization
        return withTimeoutOrNull(authorization.expiresInSeconds.seconds) {
            var interval = authorization.intervalSeconds
            var outcome: TidalLoginEvent?
            do {
                delay(interval.seconds)
                outcome = when (val result = authApi.pollDeviceToken(target.clientId, target.clientSecret, authorization.deviceCode)) {
                    TidalDevicePoll.Pending -> null
                    TidalDevicePoll.SlowDown -> {
                        interval += SLOW_DOWN_STEP_SECONDS
                        null
                    }
                    TidalDevicePoll.Expired -> EXPIRED
                    is TidalDevicePoll.Failed -> TidalLoginEvent(TidalLoginState.FAILED, result.message)
                    is TidalDevicePoll.Granted -> complete(target, result.grant)
                }
            } while (outcome == null)
            outcome
        } ?: EXPIRED
    }

    private fun complete(target: LoginTarget, grant: TidalTokenGrant): TidalLoginEvent {
        val refreshToken = grant.refreshToken
            ?: return TidalLoginEvent(TidalLoginState.FAILED, "Tidal returned no refresh token")
        return try {
            val previous = repository.loadSecret(target.name) as? TidalSessionSecret
            val secret = TidalSessionSecret(
                format = target.start.format,
                clientId = target.clientId,
                clientSecret = target.clientSecret,
                accessToken = grant.accessToken,
                refreshToken = refreshToken,
                expiresAt = grant.expiresAt,
                userId = grant.userId,
                countryCode = grant.countryCode,
                extra = previous?.takeIf { it.format == target.start.format }?.extra ?: JsonObject(emptyMap()),
            )
            repository.saveSecret(target.name, secret, TidalSessionBroker.stateOf(secret))
            TidalLoginEvent(TidalLoginState.COMPLETED)
        } catch (e: Exception) {
            TidalLoginEvent(TidalLoginState.FAILED, "Saving the Tidal session failed: ${e.message}")
        }
    }

    private companion object {
        val CANCELLED = TidalLoginEvent(TidalLoginState.CANCELLED)
        val EXPIRED = TidalLoginEvent(TidalLoginState.EXPIRED, "The login code expired")
        val RETENTION = 10.minutes
        const val SLOW_DOWN_STEP_SECONDS = 5L
    }
}
