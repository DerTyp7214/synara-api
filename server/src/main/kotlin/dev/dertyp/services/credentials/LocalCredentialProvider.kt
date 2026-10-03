package dev.dertyp.services.credentials

import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.OAuthAuthStyle
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.services.Service
import dev.dertyp.services.credentials.LocalCredentialStore.Companion.FIELD_API_KEY
import dev.dertyp.services.credentials.LocalCredentialStore.Companion.FIELD_API_SECRET
import dev.dertyp.services.credentials.LocalCredentialStore.Companion.FIELD_CLIENT_ID
import dev.dertyp.services.credentials.LocalCredentialStore.Companion.FIELD_CLIENT_SECRET
import dev.dertyp.services.credentials.LocalCredentialStore.Companion.FIELD_KEY_ID
import dev.dertyp.services.credentials.LocalCredentialStore.Companion.FIELD_P8
import dev.dertyp.services.credentials.LocalCredentialStore.Companion.FIELD_TEAM_ID
import dev.dertyp.services.credentials.LocalCredentialStore.Companion.FIELD_TOKEN
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.io.File

class LocalCredentialProvider(
    private val exchange: ClientCredentialsExchange,
    private val appleSigner: AppleDeveloperTokenSigner,
    private val store: LocalCredentialStore,
    private val pluginStore: LocalPluginCredentialStore,
) : Service(), CredentialProvider {

    private data class OAuthEntry(val tokenUrl: String, val style: OAuthAuthStyle)

    override val mode: CredentialMode = CredentialMode.LOCAL

    override suspend fun startService() {
        scope.launch { store.updates().collect { invalidate(it) } }
        scope.launch { store.watch() }
        try {
            pluginStore.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Stored plugin credentials could not be listed: ${e.message}")
        }
    }

    override fun isAvailable(name: String): Boolean = when {
        name == CredentialNames.THEAUDIODB_API -> true
        store.manages(name) -> store.isAvailable(name)
        else -> name.startsWith(CredentialNames.PLUGIN_PREFIX) && pluginStore.has(name)
    }

    override fun isManagedRemotely(name: String): Boolean = false

    override suspend fun resolve(name: String): ResolvedCredential? = try {
        when (name) {
            CredentialNames.TIDAL_API, CredentialNames.SPOTIFY_API -> oauth(name)
            CredentialNames.APPLE_MUSIC_DEVELOPER -> appleKey()?.let { appleSigner.token(it) }
            CredentialNames.THEAUDIODB_API -> ResolvedCredential.ApiKey(
                name,
                store.current(name)?.get(FIELD_API_KEY) ?: THEAUDIODB_DEFAULT_KEY
            )

            CredentialNames.YOUTUBE_API, CredentialNames.LINKRESOLVER_API, CredentialNames.ACOUSTID_API ->
                store.current(name)?.get(FIELD_API_KEY)?.let { ResolvedCredential.ApiKey(name, it) }

            CredentialNames.IMAGE_CACHE_TOKEN -> store.current(name)?.get(FIELD_TOKEN)
                ?.let { ResolvedCredential.ApiKey(name, it) }

            CredentialNames.PODCAST_INDEX_API -> store.current(name)?.let {
                ResolvedCredential.ApiKeyPair(name, it.getValue(FIELD_API_KEY), it.getValue(FIELD_API_SECRET))
            }

            else -> pluginName(name)?.let { (pluginId, key) -> pluginStore.get(pluginId, key) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Credential $name could not be resolved locally: ${e.message}")
        null
    }

    override suspend fun writeBack(name: String, expectedFingerprint: String?, files: List<CredentialFile>): Boolean =
        false

    override fun changes(): Flow<Unit> = merge(store.changes(), pluginStore.changes().map { })

    private fun invalidate(name: String) {
        when (name) {
            CredentialNames.TIDAL_API, CredentialNames.SPOTIFY_API -> exchange.invalidate(name)
            CredentialNames.APPLE_MUSIC_DEVELOPER -> appleSigner.invalidate()
        }
    }

    private suspend fun oauth(name: String): ResolvedCredential.AccessToken? {
        val entry = when (name) {
            CredentialNames.TIDAL_API -> OAuthEntry(TIDAL_TOKEN_URL, OAuthAuthStyle.BASIC)
            else -> OAuthEntry(SPOTIFY_TOKEN_URL, OAuthAuthStyle.FORM)
        }
        val values = store.current(name) ?: return null
        return exchange.exchange(
            name = name,
            tokenUrl = entry.tokenUrl,
            clientId = values.getValue(FIELD_CLIENT_ID),
            clientSecret = values.getValue(FIELD_CLIENT_SECRET),
            style = entry.style,
        )
    }

    private suspend fun appleKey(): AppleDeveloperKey? {
        val name = CredentialNames.APPLE_MUSIC_DEVELOPER
        store.stored(name)?.let {
            return AppleDeveloperKey(
                it.getValue(FIELD_TEAM_ID),
                it.getValue(FIELD_KEY_ID),
                it.getValue(FIELD_P8)
            )
        }
        val environment = store.environment(name) ?: return null
        val path = environment.getValue(FIELD_P8)
        val file = File(path)
        if (!file.exists()) {
            logger.error("p8 file not found at $path")
            return null
        }
        return AppleDeveloperKey(
            environment.getValue(FIELD_TEAM_ID),
            environment.getValue(FIELD_KEY_ID),
            file.readText()
        )
    }

    private fun pluginName(name: String): Pair<String, String>? {
        if (!name.startsWith(CredentialNames.PLUGIN_PREFIX)) return null
        val rest = name.removePrefix(CredentialNames.PLUGIN_PREFIX)
        val pluginId = rest.substringBefore(':', "").ifBlank { return null }
        val key = rest.substringAfter(':', "").ifBlank { return null }
        return pluginId to key
    }

    companion object {
        const val TIDAL_TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token"
        const val SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"
        const val THEAUDIODB_DEFAULT_KEY = "123"
    }
}
