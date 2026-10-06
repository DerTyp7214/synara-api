package dev.dertyp.config

import dev.dertyp.audio.AudioConfig
import dev.dertyp.audio.toAudioConfig
import dev.dertyp.services.MetricsConfig
import dev.dertyp.services.cover.CoverConfig
import dev.dertyp.services.cover.toCoverConfig
import dev.dertyp.services.toMetricsConfig
import io.ktor.http.Url
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.tryGetString
import io.ktor.util.logging.KtorSimpleLogger
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

class ServerConfig(config: ApplicationConfig) {
    val server: HttpServerConfig by lazy { config.toHttpServerConfig() }
    val adminClient: AdminClientConfig by lazy { config.toAdminClientConfig() }
    val database: DatabaseConfig by lazy { config.toDatabaseConfig() }
    val jwt: JwtConfig by lazy { config.toJwtConfig() }
    val redis: RedisConfig by lazy { config.toRedisConfig() }
    val setup: SetupConfig by lazy { config.toSetupConfig() }
    val backup: BackupConfig by lazy { config.toBackupConfig() }
    val library: LibraryPathsConfig by lazy { config.toLibraryPathsConfig() }
    val transcode: TranscodeConfig by lazy { config.toTranscodeConfig() }
    val audio: AudioConfig by lazy { config.toAudioConfig() }
    val cover: CoverConfig by lazy { config.toCoverConfig() }
    val metrics: MetricsConfig by lazy { config.toMetricsConfig() }
    val workers: WorkersConfig by lazy { config.toWorkersConfig() }
    val entityChanges: EntityChangeConfig by lazy { config.toEntityChangeConfig() }
    val proxy: ProxyConfig by lazy { config.toProxyConfig() }
    val credentialServer: CredentialServerConfig by lazy { config.toCredentialServerConfig() }
    val providers: ProvidersConfig = ProvidersConfig(config)
    val importers: ImportersConfig = ImportersConfig(config)
    val analysis: AnalysisConfig = AnalysisConfig(config)
}

class ProvidersConfig(config: ApplicationConfig) {
    val linkResolver: LinkResolverConfig by lazy { config.toLinkResolverConfig() }
    val youtube: YoutubeApiConfig by lazy { config.toYoutubeApiConfig() }
    val appleMusicKey: AppleMusicKeyConfig by lazy { config.toAppleMusicKeyConfig() }
    val imageCache: ImageCacheConfig by lazy { config.toImageCacheConfig() }
}

class ImportersConfig(config: ApplicationConfig) {
    val ytdlp: YtdlpConfig by lazy { config.toYtdlpConfig() }
    val gamdl: GamdlConfig by lazy { config.toGamdlConfig() }
    val tiddlAuth: TiddlAuthConfig? by lazy { config.toTiddlAuthConfig() }
}

class AnalysisConfig(config: ApplicationConfig) {
    val transcriber: TranscriberConfig by lazy { config.toTranscriberConfig() }
    val recommendations: RecommendationsConfig by lazy { config.toRecommendationsConfig() }
    val audioEmbedding: AudioEmbeddingConfig by lazy { config.toAudioEmbeddingConfig() }
}

data class HttpServerConfig(val sslSupported: Boolean)

fun ApplicationConfig.toHttpServerConfig(): HttpServerConfig = HttpServerConfig(
    sslSupported = propertyOrNull("server.sslSupported")?.getString()?.toBoolean() ?: false,
)

data class AdminClientConfig(val id: String?, val secret: String?)

fun ApplicationConfig.toAdminClientConfig(): AdminClientConfig = AdminClientConfig(
    id = propertyOrNull("client.id")?.getString(),
    secret = propertyOrNull("client.secret")?.getString(),
)

data class DatabaseConfig(
    val driverClassName: String,
    val jdbcUrl: String,
    val user: String,
    val password: String,
)

fun ApplicationConfig.toDatabaseConfig(): DatabaseConfig = DatabaseConfig(
    driverClassName = property("storage.driverClassName").getString(),
    jdbcUrl = property("storage.jdbcURL").getString(),
    user = property("storage.user").getString(),
    password = property("storage.password").getString(),
)

data class JwtConfig(
    val audience: String,
    val issuer: String,
    val realm: String,
    val secret: String,
)

fun ApplicationConfig.toJwtConfig(): JwtConfig = JwtConfig(
    audience = property("jwt.audience").getString(),
    issuer = property("jwt.issuer").getString(),
    realm = property("jwt.realm").getString(),
    secret = property("jwt.secret").getString(),
)

data class CredentialsConfig(val encryptionKey: String, val keyFile: Path)

fun ApplicationConfig.toCredentialsConfig(): CredentialsConfig = CredentialsConfig(
    encryptionKey = propertyOrNull("credentials.encryptionKey")?.getString()?.trim().orEmpty(),
    keyFile = propertyOrNull("credentials.keyFile")?.getString()?.trim()?.ifBlank { null }?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".config", "synara", "credentials.key"),
)

data class RedisConfig(
    val host: String?,
    val port: Int?,
    val useSearch: Boolean?,
    val indexPrefix: String?,
    val cacheAnimatedImages: Boolean?,
) {
    val enabled: Boolean get() = !host.isNullOrBlank()
}

fun ApplicationConfig.toRedisConfig(): RedisConfig {
    val host = propertyOrNull("redis.host")?.getString()
    if (host.isNullOrBlank()) return RedisConfig(host, null, null, null, null)
    return RedisConfig(
        host = host,
        port = propertyOrNull("redis.port")?.getString()?.toInt(),
        useSearch = propertyOrNull("redis.useSearch")?.getString()?.toBoolean(),
        indexPrefix = propertyOrNull("redis.indexPrefix")?.getString(),
        cacheAnimatedImages = propertyOrNull("redis.cacheAnimatedImages")?.getString()?.toBoolean(),
    )
}

data class SetupConfig(val fromBackup: String?, val fromMirror: MirrorSetupConfig)

data class MirrorSetupConfig(val url: String?, val username: String?, val password: String?) {
    val endpoint: Url?
        get() = url?.takeIf { it.isNotBlank() }?.let { Url(if ("://" in it) it else "http://$it") }
}

fun ApplicationConfig.toSetupConfig(): SetupConfig = SetupConfig(
    fromBackup = propertyOrNull("setup.fromBackup")?.getString(),
    fromMirror = MirrorSetupConfig(
        url = propertyOrNull("setup.fromMirror.url")?.getString(),
        username = propertyOrNull("setup.fromMirror.username")?.getString(),
        password = propertyOrNull("setup.fromMirror.password")?.getString(),
    ),
)

data class BackupConfig(val directory: Path)

fun ApplicationConfig.toBackupConfig(): BackupConfig = BackupConfig(
    directory = propertyOrNull("backup.dir")?.getString()?.ifBlank { null }?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".config", "backups"),
)

data class LibraryPathsConfig(
    val tracks: String?,
    val albums: String?,
    val playlists: String?,
    val customAudio: String,
    val images: String,
    val animatedImages: String,
    val podcastLibrary: String,
    val podcastImports: String,
    val secondaryTracks: List<String>,
)

fun ApplicationConfig.toLibraryPathsConfig(): LibraryPathsConfig = LibraryPathsConfig(
    tracks = propertyOrNull("audio.tracks")?.getString(),
    albums = propertyOrNull("audio.albums")?.getString(),
    playlists = propertyOrNull("audio.playlists")?.getString(),
    customAudio = property("audio.custom").getString(),
    images = property("data.images").getString(),
    animatedImages = property("data.animated-images").getString(),
    podcastLibrary = property("podcasts.library").getString(),
    podcastImports = property("podcasts.imports").getString(),
    secondaryTracks = try {
        propertyOrNull("audio.secondary-tracks")?.getList() ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    },
)

data class TranscodeConfig(
    val tracksPath: String?,
    val outputPath: String?,
    val autoOpusQualities: List<Int>,
    val autoAacQualities: List<Int>,
)

fun ApplicationConfig.toTranscodeConfig(): TranscodeConfig = TranscodeConfig(
    tracksPath = propertyOrNull("audio.tracks")?.getString(),
    outputPath = propertyOrNull("audio.transcode")?.getString(),
    autoOpusQualities = parseQualities(propertyOrNull("audio.autoTranscode")?.getString()),
    autoAacQualities = parseQualities(propertyOrNull("audio.autoTranscodeAac")?.getString()),
)

private fun parseQualities(value: String?): List<Int> =
    value?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 0 } ?: emptyList()

data class WorkersConfig(val threadMultiplier: Double)

fun ApplicationConfig.toWorkersConfig(): WorkersConfig = WorkersConfig(
    threadMultiplier = propertyOrNull("workers.threadMultiplier")?.getString()?.toDoubleOrNull() ?: 1.0,
)

data class EntityChangeConfig(val retentionDays: Long = 30)

fun ApplicationConfig.toEntityChangeConfig(): EntityChangeConfig = EntityChangeConfig(
    retentionDays = propertyOrNull("entityChanges.retentionDays")?.getString()?.toLongOrNull()?.coerceAtLeast(1) ?: 30,
)

data class ProxyConfig(
    val hostname: String?,
    val controlPort: Int?,
    val ssl: Boolean,
    val id: String?,
    val name: String?,
    val key: String?,
)

fun ApplicationConfig.toProxyConfig(): ProxyConfig = ProxyConfig(
    hostname = propertyOrNull("proxy.hostname")?.getString(),
    controlPort = propertyOrNull("proxy.controlPort")?.getString()?.toInt(),
    ssl = propertyOrNull("proxy.ssl")?.getString()?.toBoolean() ?: false,
    id = propertyOrNull("proxy.id")?.getString(),
    name = propertyOrNull("proxy.name")?.getString(),
    key = propertyOrNull("proxy.key")?.getString(),
)

data class CredentialServerConfig(
    val url: String?,
    val clientId: String?,
    val clientSecret: String?,
    val adminKey: String?,
) {
    companion object {
        const val URL_PATH = "credentialServer.url"
        const val CLIENT_ID_PATH = "credentialServer.clientId"
        const val CLIENT_SECRET_PATH = "credentialServer.clientSecret"
        const val ADMIN_KEY_PATH = "credentialServer.adminKey"
    }
}

fun ApplicationConfig.toCredentialServerConfig(): CredentialServerConfig = CredentialServerConfig(
    url = propertyOrNull(CredentialServerConfig.URL_PATH)?.getString()?.trim()?.ifBlank { null },
    clientId = propertyOrNull(CredentialServerConfig.CLIENT_ID_PATH)?.getString()?.trim()?.ifBlank { null },
    clientSecret = propertyOrNull(CredentialServerConfig.CLIENT_SECRET_PATH)?.getString()?.trim()?.ifBlank { null },
    adminKey = propertyOrNull(CredentialServerConfig.ADMIN_KEY_PATH)?.getString()?.trim()?.ifBlank { null },
)

enum class ProviderCredentialKeys(val idKey: String, val secretKey: String) {
    NONE("", ""),
    TIDAL("tidal.clientId", "tidal.clientSecret"),
    SPOTIFY("spotify.clientId", "spotify.clientSecret"),
    THE_AUDIO_DB("theaudiodb.apiKey", ""),
}

data class ClientCredentials(val keys: ProviderCredentialKeys, val clientId: String?, val clientSecret: String?)

fun ApplicationConfig.toClientCredentials(keys: ProviderCredentialKeys): ClientCredentials = ClientCredentials(
    keys = keys,
    clientId = keys.idKey.takeIf { it.isNotEmpty() }?.let { propertyOrNull(it)?.getString() },
    clientSecret = keys.secretKey.takeIf { it.isNotEmpty() }?.let { propertyOrNull(it)?.getString() },
)

data class AppleMusicKeyConfig(val teamId: String?, val keyId: String?, val p8Path: String?) {
    val complete: Boolean
        get() = !teamId.isNullOrBlank() && !keyId.isNullOrBlank() && !p8Path.isNullOrBlank()
}

fun ApplicationConfig.toAppleMusicKeyConfig(): AppleMusicKeyConfig = AppleMusicKeyConfig(
    teamId = propertyOrNull("appleMusic.teamId")?.getString(),
    keyId = propertyOrNull("appleMusic.keyId")?.getString(),
    p8Path = propertyOrNull("appleMusic.p8Path")?.getString(),
)

fun ApplicationConfig.appleMusicStorefront(): String =
    propertyOrNull("appleMusic.storefront")?.getString()?.takeUnless { it.isBlank() } ?: "us"

data class ImageCacheConfig(val url: String?, val token: String?)

fun ApplicationConfig.toImageCacheConfig(): ImageCacheConfig = ImageCacheConfig(
    url = propertyOrNull("imageCache.url")?.getString(),
    token = propertyOrNull("imageCache.token")?.getString(),
)

data class LinkResolverConfig(val apiKey: String)

fun ApplicationConfig.toLinkResolverConfig(): LinkResolverConfig = LinkResolverConfig(
    apiKey = tryGetString("linkresolver.apiKey") ?: "",
)

data class YoutubeApiConfig(val apiKey: String?)

fun ApplicationConfig.toYoutubeApiConfig(): YoutubeApiConfig = YoutubeApiConfig(
    apiKey = propertyOrNull("youtube.apiKey")?.getString(),
)

data class TiddlAuthConfig(val clientId: String, val clientSecret: String)

fun ApplicationConfig.toTiddlAuthConfig(): TiddlAuthConfig? {
    val raw = propertyOrNull("tiddl.auth")?.getString()?.trim()?.ifBlank { null } ?: return null
    val clientId = raw.substringBefore(';').trim()
    val clientSecret = raw.substringAfter(';', "").trim()
    if (clientId.isEmpty() || clientSecret.isEmpty()) {
        KtorSimpleLogger("ServerConfig").warn("TIDDL_AUTH is set but is not in the form <client_id>;<client_secret>, ignoring it")
        return null
    }
    return TiddlAuthConfig(clientId, clientSecret)
}

data class YtdlpConfig(val configPath: String?)

fun ApplicationConfig.toYtdlpConfig(): YtdlpConfig = YtdlpConfig(
    configPath = propertyOrNull("ytdlp.config")?.getString(),
)

data class GamdlConfig(val cookiesPath: String, val wvdPath: String?, val codecSong: String?)

fun ApplicationConfig.toGamdlConfig(): GamdlConfig = GamdlConfig(
    cookiesPath = propertyOrNull("gamdl.cookiesPath")?.getString()?.ifBlank { null } ?: "cookies.txt",
    wvdPath = propertyOrNull("gamdl.wvdPath")?.getString()?.ifBlank { null },
    codecSong = propertyOrNull("gamdl.codecSong")?.getString()?.ifBlank { null },
)

data class TranscriberConfig(val url: String?) {
    val configured: Boolean get() = !url.isNullOrBlank()
    val baseUrl: String get() = url ?: "http://localhost:8000"
}

fun ApplicationConfig.toTranscriberConfig(): TranscriberConfig = TranscriberConfig(
    url = propertyOrNull("transcriber.url")?.getString(),
)

data class RecommendationsConfig(val dataDir: File?)

fun ApplicationConfig.toRecommendationsConfig(): RecommendationsConfig = RecommendationsConfig(
    dataDir = propertyOrNull("recsys.dataDir")?.getString()?.takeIf { it.isNotBlank() }?.let { File(it) },
)

data class AudioEmbeddingConfig(val url: String?)

fun ApplicationConfig.toAudioEmbeddingConfig(): AudioEmbeddingConfig = AudioEmbeddingConfig(
    url = propertyOrNull("audioEmbed.url")?.getString()?.takeIf { it.isNotBlank() },
)
