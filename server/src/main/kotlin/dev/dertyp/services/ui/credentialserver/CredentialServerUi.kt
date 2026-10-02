package dev.dertyp.services.ui.credentialserver

import dev.dertyp.config.ServerConfig
import dev.dertyp.core.sha256
import dev.dertyp.credentials.CredentialKind
import dev.dertyp.credentials.CredentialStatus
import dev.dertyp.credentials.TidalLoginSession
import dev.dertyp.plugins.PluginManager
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.credentials.CredentialProvider
import dev.dertyp.services.credentials.CredentialServerConnection
import dev.dertyp.services.credentials.CredentialServerConnectionSource
import dev.dertyp.services.credentials.LocalCredentialStore
import dev.dertyp.services.credentials.admin.CredentialServerAdminClient
import dev.dertyp.services.credentials.admin.CredentialServerAdminException
import dev.dertyp.ui.UiTone
import io.ktor.http.HttpStatusCode
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

const val CREDENTIAL_SERVER_UI_SOURCE = "credentialserver"

enum class CredentialServerLinkStatus { LOCAL, CONNECTED, UNREACHABLE }

class CredentialServerSecretReveal {
    private val secrets = ConcurrentHashMap<Pair<UUID, String>, String>()

    fun put(userId: UUID, clientId: String, secret: String) {
        secrets[userId to clientId] = secret
    }

    fun take(userId: UUID, clientId: String): String? = secrets.remove(userId to clientId)
}

class CredentialServerTidalLogins {
    data class Entry(val credentialName: String, val session: TidalLoginSession)

    private val logins = ConcurrentHashMap<Pair<UUID, String>, Entry>()

    fun put(userId: UUID, credentialName: String, session: TidalLoginSession) {
        logins[userId to credentialName] = Entry(credentialName, session)
    }

    fun active(userId: UUID, credentialName: String): TidalLoginSession? {
        val entry = logins[userId to credentialName] ?: return null
        if (entry.session.expiresAt <= System.currentTimeMillis()) {
            logins.remove(userId to credentialName, entry)
            return null
        }
        return entry.session
    }

    fun byLoginId(userId: UUID, loginId: String): Entry? =
        logins.entries.firstOrNull { it.key.first == userId && it.value.session.loginId == loginId }?.value

    fun remove(userId: UUID, credentialName: String) {
        logins.remove(userId to credentialName)
    }
}

class CredentialServerUiContext(
    val admin: CredentialServerAdminClient,
    val connection: CredentialServerConnectionSource,
    val provider: CredentialProvider,
    val localStore: LocalCredentialStore,
    val serverConfig: ServerConfig,
    val pluginManager: PluginManager,
) {
    private data class AdminProbe(val fingerprint: String, val admin: Boolean, val checkedAt: Long)

    val reveal = CredentialServerSecretReveal()
    val tidalLogins = CredentialServerTidalLogins()

    @Volatile
    private var adminProbe: AdminProbe? = null

    fun changes(): Flow<Unit> = merge(connection.changes().map { }, admin.changes(), provider.changes())

    suspend fun connection(): CredentialServerConnection = connection.current() ?: CredentialServerConnection.NONE

    suspend fun isAdmin(): Boolean {
        val current = connection()
        val baseUrl = current.baseUrl ?: return false
        val adminKey = current.adminKey?.takeIf { it.isNotBlank() } ?: return false
        val fingerprint = "$baseUrl\n$adminKey".toByteArray(Charsets.UTF_8).sha256()
        val now = System.currentTimeMillis()
        adminProbe?.takeIf { it.fingerprint == fingerprint && now - it.checkedAt < ADMIN_PROBE_TTL.inWholeMilliseconds }?.let { return it.admin }
        val accepted = try {
            withTimeoutOrNull(HEALTH_TIMEOUT) {
                admin.listClients()
                true
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: CredentialServerAdminException) {
            if (e.status != HttpStatusCode.Unauthorized && e.status != HttpStatusCode.ServiceUnavailable) {
                logger.warn("The credential server admin key could not be checked: ${e.message}")
            }
            false
        } catch (e: Exception) {
            logger.warn("The credential server admin key could not be checked: ${e.message}")
            false
        }
        adminProbe = AdminProbe(fingerprint, accepted, now)
        return accepted
    }

    fun forgetAdminProbe() {
        adminProbe = null
    }

    suspend fun linkStatus(): CredentialServerLinkStatus {
        if (connection().baseUrl == null) return CredentialServerLinkStatus.LOCAL
        val healthy = try {
            withTimeoutOrNull(HEALTH_TIMEOUT) { admin.health().ok } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return if (healthy) CredentialServerLinkStatus.CONNECTED else CredentialServerLinkStatus.UNREACHABLE
    }

    fun defaultServerName(): String = serverConfig.proxy.name?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_NAME

    fun contributions(): List<UiContribution> = listOf(
        CredentialsEntryContribution(this),
        CredentialsOverviewContribution(this),
        LocalCredentialContribution(this),
        CredentialServerClientContribution(this),
        CredentialServerCredentialContribution(this),
    )

    companion object {
        const val DEFAULT_SERVER_NAME = "Synara"
        private val HEALTH_TIMEOUT = 5.seconds
        private val ADMIN_PROBE_TTL = 30.seconds
        private val logger = KtorSimpleLogger("CredentialServerUiContext")
    }
}

internal object CredentialServerPages {
    const val ENTRY = "credentials.entry"
    const val OVERVIEW = "credentials.overview"
    const val LOCAL = "credentials.local"
    const val CLIENT = "credentialserver.client"
    const val CREDENTIAL = "credentialserver.credential"
    const val PARAM_ID = "id"
    const val PARAM_NAME = "name"
    const val PARAM_KIND = "kind"
    const val PREFIX = "credentialserver"
}

internal fun writeBackCapable(kind: CredentialKind): Boolean =
    kind == CredentialKind.TIDAL_DEVICE_SESSION || kind == CredentialKind.FILE

internal fun UiRenderScope.linkStatusText(status: CredentialServerLinkStatus): String = when (status) {
    CredentialServerLinkStatus.LOCAL -> t("credentialserver.status.local")
    CredentialServerLinkStatus.CONNECTED -> t("credentialserver.status.connected")
    CredentialServerLinkStatus.UNREACHABLE -> t("credentialserver.status.unreachable")
}

internal fun linkStatusTone(status: CredentialServerLinkStatus): UiTone = when (status) {
    CredentialServerLinkStatus.LOCAL -> UiTone.MUTED
    CredentialServerLinkStatus.CONNECTED -> UiTone.SUCCESS
    CredentialServerLinkStatus.UNREACHABLE -> UiTone.ERROR
}

internal fun UiRenderScope.statusText(status: CredentialStatus): String = t("credentialserver.credentialStatus.${status.name}")

internal fun statusTone(status: CredentialStatus): UiTone = when (status) {
    CredentialStatus.OK -> UiTone.SUCCESS
    CredentialStatus.EXPIRING -> UiTone.WARNING
    CredentialStatus.EXPIRED, CredentialStatus.NEEDS_LOGIN, CredentialStatus.ERROR -> UiTone.ERROR
}

internal fun UiRenderScope.kindText(kind: CredentialKind): String = t("credentialserver.kind.${kind.name}")

internal fun formatTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(epochMillis))

internal fun UiRenderScope.errorText(error: Throwable): String = when (error) {
    is CredentialServerAdminException -> error.error?.message ?: error.message ?: t("credentialserver.error.generic")
    else -> error.message ?: t("credentialserver.error.generic")
}

internal fun Throwable.isNotFound(): Boolean =
    this is CredentialServerAdminException && status == HttpStatusCode.NotFound
