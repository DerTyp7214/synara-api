package dev.dertyp.credentials

object CredentialProtocol {
    const val PROTOCOL_VERSION = 1
    const val HEALTH_PATH = "/health"
    const val TOKEN_PATH = "/token"
    const val CREDENTIALS_PATH = "/credentials"
    const val ADMIN_PREFIX = "/admin"
    const val ADMIN_KEY_HEADER = "X-Admin-Key"

    fun credentialPath(name: String) = "$CREDENTIALS_PATH/$name"

    fun writeBackPath(name: String) = "$CREDENTIALS_PATH/$name/files"
}

object CredentialNames {
    const val TIDAL_API = "tidal.api"
    const val SPOTIFY_API = "spotify.api"
    const val APPLE_MUSIC_DEVELOPER = "applemusic.developer"
    const val YOUTUBE_API = "youtube.api"
    const val ACOUSTID_API = "acoustid.api"
    const val PODCAST_INDEX_API = "podcastindex.api"
    const val THEAUDIODB_API = "theaudiodb.api"
    const val LINKRESOLVER_API = "linkresolver.api"
    const val IMAGE_CACHE_TOKEN = "imagecache.token"
    const val IMPORTER_TIDDL = "importer.tiddl"
    const val IMPORTER_TDN = "importer.tdn"
    const val IMPORTER_GAMDL = "importer.gamdl"

    val CORE: List<String> = listOf(
        TIDAL_API,
        SPOTIFY_API,
        APPLE_MUSIC_DEVELOPER,
        YOUTUBE_API,
        ACOUSTID_API,
        PODCAST_INDEX_API,
        THEAUDIODB_API,
        LINKRESOLVER_API,
        IMAGE_CACHE_TOKEN,
        IMPORTER_TIDDL,
        IMPORTER_TDN,
        IMPORTER_GAMDL,
    )

    const val PLUGIN_PREFIX = "plugin:"

    fun plugin(pluginId: String, name: String) = "$PLUGIN_PREFIX$pluginId:$name"

    val NAME_REGEX = Regex("[a-z0-9._:-]{1,128}")

    fun isValid(name: String) = NAME_REGEX.matches(name)
}

object CredentialFileRoles {
    const val TIDDL_AUTH = "auth.json"
    const val TDN_TOKEN = "token.json"
    const val GAMDL_COOKIES = "cookies.txt"
    const val GAMDL_WVD = "device.wvd"
}
