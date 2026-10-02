package dev.dertyp.services.credentials

import dev.dertyp.credentials.CredentialNames
import dev.dertyp.plugins.PluginSettings
import dev.dertyp.services.metadata.AcoustIdCredentialSource
import dev.dertyp.services.podcast.index.PodcastIndexCredentialSource
import dev.dertyp.services.podcast.index.PodcastIndexCredentials
import dev.dertyp.services.ui.PluginSettingsService
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

data class LocalCredentialField(
    val name: String,
    val settingKey: String,
    val configPath: String,
    val secret: Boolean,
    val file: Boolean = false,
)

class LocalCredentialEntry<T : Any>(
    val name: String,
    val fields: List<LocalCredentialField>,
    private val source: CredentialSource<T>,
    private val values: (T) -> Map<String, String>,
) {
    suspend fun origin(): CredentialOrigin = source.origin()

    suspend fun stored(): Map<String, String>? = source.stored()?.let(values)

    fun environment(): Map<String, String>? = source.fromEnvironment()?.let(values)

    suspend fun current(): Map<String, String>? = stored() ?: environment()

    suspend fun store(updates: Map<String, String>) {
        val mapped = fields.filter { it.name in updates }.associate { it.settingKey to updates[it.name] }
        if (mapped.isNotEmpty()) source.store(mapped)
    }

    suspend fun clear() = source.clear()
}

class FieldCredentialSource(
    settings: PluginSettings,
    config: ApplicationConfig,
    cipher: CredentialCipher,
    override val fields: List<CredentialField>,
) : CredentialSource<Map<String, String>>(settings, config, cipher) {
    override fun build(values: Map<String, String>): Map<String, String>? =
        values.takeIf { fields.all { field -> field.settingKey in values } }
}

class LocalCredentialStore(
    settingsService: PluginSettingsService,
    config: ApplicationConfig,
    cipher: CredentialCipher,
    private val acoustIdSource: AcoustIdCredentialSource,
) {
    private val logger = KtorSimpleLogger("LocalCredentialStore")
    private val settings = settingsService.forPlugin(PLUGIN_ID)
    private val podcastIndexSource =
        PodcastIndexCredentialSource(settingsService.forPlugin(PodcastIndexCredentialSource.PLUGIN_ID), config, cipher)
    private val updates = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var storedNames: Set<String> = emptySet()

    val entries: List<LocalCredentialEntry<*>> = listOf(
        LocalCredentialEntry(
            CredentialNames.ACOUSTID_API,
            listOf(LocalCredentialField(FIELD_API_KEY, AcoustIdCredentialSource.KEY_API_KEY, "acoustid.apiKey", secret = true)),
            acoustIdSource,
        ) { mapOf(FIELD_API_KEY to it) },
        LocalCredentialEntry(
            CredentialNames.PODCAST_INDEX_API,
            listOf(
                LocalCredentialField(FIELD_API_KEY, PodcastIndexCredentialSource.KEY_API_KEY, "podcastIndex.apiKey", secret = true),
                LocalCredentialField(FIELD_API_SECRET, PodcastIndexCredentialSource.KEY_API_SECRET, "podcastIndex.apiSecret", secret = true),
            ),
            podcastIndexSource,
        ) { credentials: PodcastIndexCredentials -> mapOf(FIELD_API_KEY to credentials.apiKey, FIELD_API_SECRET to credentials.apiSecret) },
        generic(config, cipher, CredentialNames.THEAUDIODB_API, field(CredentialNames.THEAUDIODB_API, FIELD_API_KEY, "theaudiodb.apiKey", secret = true)),
        generic(config, cipher, CredentialNames.YOUTUBE_API, field(CredentialNames.YOUTUBE_API, FIELD_API_KEY, "youtube.apiKey", secret = true)),
        generic(config, cipher, CredentialNames.LINKRESOLVER_API, field(CredentialNames.LINKRESOLVER_API, FIELD_API_KEY, "linkresolver.apiKey", secret = true)),
        generic(config, cipher, CredentialNames.IMAGE_CACHE_TOKEN, field(CredentialNames.IMAGE_CACHE_TOKEN, FIELD_TOKEN, "imageCache.token", secret = true)),
        generic(
            config, cipher, CredentialNames.TIDAL_API,
            field(CredentialNames.TIDAL_API, FIELD_CLIENT_ID, "tidal.clientId", secret = false),
            field(CredentialNames.TIDAL_API, FIELD_CLIENT_SECRET, "tidal.clientSecret", secret = true),
        ),
        generic(
            config, cipher, CredentialNames.SPOTIFY_API,
            field(CredentialNames.SPOTIFY_API, FIELD_CLIENT_ID, "spotify.clientId", secret = false),
            field(CredentialNames.SPOTIFY_API, FIELD_CLIENT_SECRET, "spotify.clientSecret", secret = true),
        ),
        generic(
            config, cipher, CredentialNames.APPLE_MUSIC_DEVELOPER,
            field(CredentialNames.APPLE_MUSIC_DEVELOPER, FIELD_TEAM_ID, "appleMusic.teamId", secret = false),
            field(CredentialNames.APPLE_MUSIC_DEVELOPER, FIELD_KEY_ID, "appleMusic.keyId", secret = false),
            field(CredentialNames.APPLE_MUSIC_DEVELOPER, FIELD_P8, "appleMusic.p8Path", secret = true, file = true),
        ),
    )

    private val byName: Map<String, LocalCredentialEntry<*>> = entries.associateBy { it.name }

    private fun field(name: String, field: String, configPath: String, secret: Boolean, file: Boolean = false) =
        LocalCredentialField(field, "$name.$field", configPath, secret, file)

    private fun generic(config: ApplicationConfig, cipher: CredentialCipher, name: String, vararg fields: LocalCredentialField) =
        LocalCredentialEntry(
            name,
            fields.toList(),
            FieldCredentialSource(settings, config, cipher, fields.map { CredentialField(it.settingKey, it.configPath) }),
        ) { stored: Map<String, String> -> fields.associate { it.name to stored.getValue(it.settingKey) } }

    fun entry(name: String): LocalCredentialEntry<*>? = byName[name]

    fun manages(name: String): Boolean = name in byName

    fun hasStored(name: String): Boolean = name in storedNames

    fun isAvailable(name: String): Boolean {
        val entry = byName[name] ?: return false
        return name in storedNames || entry.environment() != null
    }

    suspend fun origin(name: String): CredentialOrigin = byName[name]?.origin() ?: CredentialOrigin.NONE

    suspend fun stored(name: String): Map<String, String>? {
        val entry = byName[name] ?: return null
        return try {
            entry.stored()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Stored credential $name could not be read: ${e.message}")
            null
        }
    }

    fun environment(name: String): Map<String, String>? = byName[name]?.environment()

    suspend fun current(name: String): Map<String, String>? = stored(name) ?: environment(name)

    suspend fun store(name: String, values: Map<String, String>) {
        val entry = requireNotNull(byName[name]) { "Unknown local credential $name" }
        entry.store(values)
        refresh()
        updates.emit(name)
    }

    suspend fun clear(name: String) {
        val entry = requireNotNull(byName[name]) { "Unknown local credential $name" }
        entry.clear()
        refresh()
        updates.emit(name)
    }

    suspend fun refresh() {
        storedNames = entries.filter { stored(it.name) != null }.mapTo(mutableSetOf()) { it.name }
    }

    suspend fun watch() {
        merge(acoustIdSource.changes(), podcastIndexSource.changes()).collect { refresh() }
    }

    fun updates(): Flow<String> = updates.asSharedFlow()

    fun changes(): Flow<Unit> = merge(
        updates.map { },
        acoustIdSource.changes().map { },
        podcastIndexSource.changes().map { },
    )

    companion object {
        const val PLUGIN_ID = "credentials"
        const val FIELD_API_KEY = "apiKey"
        const val FIELD_API_SECRET = "apiSecret"
        const val FIELD_TOKEN = "token"
        const val FIELD_CLIENT_ID = "clientId"
        const val FIELD_CLIENT_SECRET = "clientSecret"
        const val FIELD_TEAM_ID = "teamId"
        const val FIELD_KEY_ID = "keyId"
        const val FIELD_P8 = "p8"
    }
}
