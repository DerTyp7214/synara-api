package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.CredentialInput
import dev.dertyp.credentials.CredentialKind
import dev.dertyp.credentials.CredentialPreset
import dev.dertyp.credentials.CredentialStatus
import dev.dertyp.credentials.CredentialTestResult
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.credentials.WriteBackRequest
import dev.dertyp.credentials.WriteBackResult
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

class CredentialResolver(private val repository: SecretRepository, httpClient: HttpClient) {
    private val oauthBroker = OAuthClientCredentialsBroker(httpClient)
    private val appleBroker = AppleDeveloperTokenBroker()
    private val apiKeyBroker = ApiKeyBroker()
    private val tidalBroker = TidalSessionBroker(TidalAuthApi(httpClient))
    private val fileBroker = FileBroker()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun resolve(name: String): ResolvedCredential = lockFor(name).withLock {
        val secret = load(name)
        val resolution = try {
            dispatch(name, secret)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CredentialException) {
            recordFailure(name, secret, e)
            throw e
        } catch (e: Exception) {
            val failure = CredentialException(CredentialErrorCode.UPSTREAM_FAILED, e.message ?: e::class.simpleName.orEmpty())
            recordFailure(name, secret, failure)
            throw failure
        }
        val updated = resolution.updatedSecret
        if (updated != null) repository.saveSecret(name, updated, resolution.state)
        else repository.updateState(name, resolution.state)
        resolution.credential
    }

    suspend fun writeBack(name: String, request: WriteBackRequest): WriteBackResult = lockFor(name).withLock {
        val (updated, state) = when (val secret = load(name)) {
            is TidalSessionSecret -> tidalBroker.writeBack(secret, request)
            is FileSecret -> fileBroker.writeBack(secret, request)
            else -> throw CredentialException(CredentialErrorCode.INVALID, "$name does not accept write-back")
        }
        repository.saveSecret(name, updated, state)
        WriteBackResult(state.fingerprint)
    }

    suspend fun test(name: String): CredentialTestResult = try {
        val credential = resolve(name)
        CredentialTestResult(ok = true, expiresAt = credential.expiresAt, message = null)
    } catch (e: CredentialException) {
        if (e.code == CredentialErrorCode.NOT_FOUND) throw e
        CredentialTestResult(ok = false, expiresAt = null, message = "${e.code}: ${e.message}")
    }

    fun toStoredSecret(kind: CredentialKind, input: CredentialInput, existing: StoredSecret?): StoredSecret {
        val inputKind = kindOf(input)
        if (inputKind != kind) {
            throw CredentialException(CredentialErrorCode.INVALID, "Input $inputKind does not match kind $kind")
        }
        return when (input) {
            is CredentialInput.OAuthClientCredentialsInput -> {
                val previous = existing as? OAuthSecret
                OAuthSecret(
                    clientId = keep(input.clientId, previous?.clientId, "clientId"),
                    clientSecret = keep(input.clientSecret, previous?.clientSecret, "clientSecret"),
                    tokenUrl = keep(input.tokenUrl, previous?.tokenUrl, "tokenUrl"),
                    authStyle = input.authStyle,
                    scope = input.scope?.takeIf { it.isNotBlank() },
                )
            }
            is CredentialInput.AppleDeveloperKeyInput -> {
                val previous = existing as? AppleSecret
                if (input.ttlSeconds !in 1..AppleDeveloperTokenBroker.MAX_TTL_SECONDS) {
                    throw CredentialException(
                        CredentialErrorCode.INVALID,
                        "ttlSeconds must be between 1 and ${AppleDeveloperTokenBroker.MAX_TTL_SECONDS}",
                    )
                }
                AppleSecret(
                    teamId = keep(input.teamId, previous?.teamId, "teamId"),
                    keyId = keep(input.keyId, previous?.keyId, "keyId"),
                    p8Pem = keep(input.p8Pem, previous?.p8Pem, "p8Pem").also { AppleDeveloperTokenBroker.parsePrivateKey(it) },
                    ttlSeconds = input.ttlSeconds,
                )
            }
            is CredentialInput.ApiKeyInput ->
                ApiKeySecret(keep(input.key, (existing as? ApiKeySecret)?.key, "key"))
            is CredentialInput.ApiKeyPairInput -> {
                val previous = existing as? ApiKeyPairSecret
                ApiKeyPairSecret(
                    key = keep(input.key, previous?.key, "key"),
                    secret = keep(input.secret, previous?.secret, "secret"),
                )
            }
            is CredentialInput.TidalSessionInput -> tidalSecret(input, existing as? TidalSessionSecret)
            is CredentialInput.FileInput -> fileSecret(input.files, existing as? FileSecret)
        }
    }

    private fun kindOf(input: CredentialInput): CredentialKind = when (input) {
        is CredentialInput.OAuthClientCredentialsInput -> CredentialKind.OAUTH_CLIENT_CREDENTIALS
        is CredentialInput.AppleDeveloperKeyInput -> CredentialKind.APPLE_DEVELOPER_KEY
        is CredentialInput.ApiKeyInput -> CredentialKind.API_KEY
        is CredentialInput.ApiKeyPairInput -> CredentialKind.API_KEY_PAIR
        is CredentialInput.TidalSessionInput -> CredentialKind.TIDAL_DEVICE_SESSION
        is CredentialInput.FileInput -> CredentialKind.FILE
    }

    fun initialState(secret: StoredSecret): CredentialStateUpdate = when (secret) {
        is TidalSessionSecret -> TidalSessionBroker.stateOf(secret)
        is FileSecret -> FileBroker.stateOf(secret)
        else -> CredentialStateUpdate(CredentialStatus.OK, null, null, null)
    }

    fun invalidate(name: String) {
        oauthBroker.invalidate(name)
        appleBroker.invalidate(name)
    }

    fun presets(): List<CredentialPreset> = CredentialPresets.all

    private fun lockFor(name: String) = locks.computeIfAbsent(name) { Mutex() }

    private fun load(name: String): StoredSecret =
        repository.loadSecret(name) ?: throw CredentialException(CredentialErrorCode.NOT_FOUND, "Unknown credential $name")

    private suspend fun dispatch(name: String, secret: StoredSecret): BrokerResolution<out StoredSecret> = when (secret) {
        is OAuthSecret -> oauthBroker.resolve(name, secret)
        is AppleSecret -> appleBroker.resolve(name, secret)
        is ApiKeySecret, is ApiKeyPairSecret -> apiKeyBroker.resolve(name, secret)
        is TidalSessionSecret -> tidalBroker.resolve(name, secret)
        is FileSecret -> fileBroker.resolve(name, secret)
    }

    private fun recordFailure(name: String, secret: StoredSecret, failure: CredentialException) {
        val status = if (failure.code == CredentialErrorCode.NEEDS_LOGIN) CredentialStatus.NEEDS_LOGIN else CredentialStatus.ERROR
        repository.updateState(name, initialState(secret).copy(status = status, statusMessage = failure.message))
    }

    private fun tidalSecret(input: CredentialInput.TidalSessionInput, previous: TidalSessionSecret?): TidalSessionSecret {
        val base = TidalSessionSecret(
            format = input.format,
            clientId = keep(input.clientId, previous?.clientId, "clientId"),
            clientSecret = keep(input.clientSecret, previous?.clientSecret, "clientSecret"),
            accessToken = previous?.accessToken,
            refreshToken = previous?.refreshToken,
            expiresAt = previous?.expiresAt,
            userId = previous?.userId,
            countryCode = previous?.countryCode,
            extra = previous?.takeIf { it.format == input.format }?.extra ?: JsonObject(emptyMap()),
        )
        val content = input.authFileContent?.takeIf { it.isNotBlank() } ?: return base
        return TidalAuthFormats.adopt(base, TidalAuthFormats.parse(input.format, content))
    }

    private fun fileSecret(files: List<CredentialFile>, previous: FileSecret?): FileSecret {
        val existing = previous?.files.orEmpty().associateBy { it.role }
        val provided = files.mapNotNull { file ->
            if (file.contentBase64.isBlank()) existing[file.role]
            else file.also { FileContents.decode(it) }
        }
        val providedRoles = provided.map { it.role }.toSet()
        val merged = previous?.files.orEmpty().filter { it.role !in providedRoles } + provided
        if (merged.isEmpty()) throw CredentialException(CredentialErrorCode.INVALID, "At least one file is required")
        if (merged.map { it.role }.toSet().size != merged.size) {
            throw CredentialException(CredentialErrorCode.INVALID, "File roles must be unique")
        }
        return FileSecret(merged)
    }

    private fun keep(value: String, previous: String?, field: String): String =
        value.takeIf { it.isNotBlank() } ?: previous
        ?: throw CredentialException(CredentialErrorCode.INVALID, "$field is required")
}
