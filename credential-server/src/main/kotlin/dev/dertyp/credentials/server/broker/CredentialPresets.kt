package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialFileRoles
import dev.dertyp.credentials.CredentialKind
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.CredentialPreset
import dev.dertyp.credentials.OAuthAuthStyle
import dev.dertyp.credentials.TidalSessionFormat

data class TidalClient(val id: String, val secret: String)

object CredentialPresets {
    const val SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"
    const val TIDAL_IMPORTER_CLIENT_ID = "4N3n6Q1x95LL5K7p"
    const val TIDAL_IMPORTER_CLIENT_SECRET = "oKOXfJW371cX6xaZ0PyhgGNBdNLlBZd4AKKYougMjik="

    private val tidalImporterClient = TidalClient(TIDAL_IMPORTER_CLIENT_ID, TIDAL_IMPORTER_CLIENT_SECRET)

    private val defaultTidalClients: Map<String, TidalClient> = mapOf(
        CredentialNames.IMPORTER_TIDDL to tidalImporterClient,
        CredentialNames.IMPORTER_TDN to tidalImporterClient,
    )

    fun tidalClient(name: String, clientId: String?, clientSecret: String?, previous: TidalSessionSecret?): TidalClient {
        val default = defaultTidalClients[name]
        return TidalClient(
            id = clientId?.takeIf { it.isNotBlank() } ?: previous?.clientId ?: default?.id
                ?: throw CredentialException(CredentialErrorCode.INVALID, "A Tidal client id is required"),
            secret = clientSecret?.takeIf { it.isNotBlank() } ?: previous?.clientSecret ?: default?.secret
                ?: throw CredentialException(CredentialErrorCode.INVALID, "A Tidal client secret is required"),
        )
    }

    val all: List<CredentialPreset> = listOf(
        CredentialPreset(
            name = CredentialNames.TIDAL_API,
            kind = CredentialKind.OAUTH_CLIENT_CREDENTIALS,
            description = "Tidal API client credentials for metadata lookups",
            tokenUrl = TidalAuthApi.TOKEN_URL,
            authStyle = OAuthAuthStyle.BASIC,
        ),
        CredentialPreset(
            name = CredentialNames.SPOTIFY_API,
            kind = CredentialKind.OAUTH_CLIENT_CREDENTIALS,
            description = "Spotify Web API client credentials for metadata lookups",
            tokenUrl = SPOTIFY_TOKEN_URL,
            authStyle = OAuthAuthStyle.FORM,
        ),
        CredentialPreset(
            name = CredentialNames.APPLE_MUSIC_DEVELOPER,
            kind = CredentialKind.APPLE_DEVELOPER_KEY,
            description = "Apple Music developer key (.p8) used to sign developer tokens",
        ),
        CredentialPreset(
            name = CredentialNames.YOUTUBE_API,
            kind = CredentialKind.API_KEY,
            description = "YouTube Data API key",
        ),
        CredentialPreset(
            name = CredentialNames.ACOUSTID_API,
            kind = CredentialKind.API_KEY,
            description = "AcoustID application API key",
        ),
        CredentialPreset(
            name = CredentialNames.THEAUDIODB_API,
            kind = CredentialKind.API_KEY,
            description = "TheAudioDB API key",
        ),
        CredentialPreset(
            name = CredentialNames.LINKRESOLVER_API,
            kind = CredentialKind.API_KEY,
            description = "Link resolver API key",
        ),
        CredentialPreset(
            name = CredentialNames.IMAGE_CACHE_TOKEN,
            kind = CredentialKind.API_KEY,
            description = "Image cache access token",
        ),
        CredentialPreset(
            name = CredentialNames.PODCAST_INDEX_API,
            kind = CredentialKind.API_KEY_PAIR,
            description = "Podcast Index API key and secret",
        ),
        CredentialPreset(
            name = CredentialNames.IMPORTER_TIDDL,
            kind = CredentialKind.TIDAL_DEVICE_SESSION,
            description = "Tidal session for the tiddl importer, refreshed on every hand-out",
            format = TidalSessionFormat.TIDDL,
            fileRoles = listOf(CredentialFileRoles.TIDDL_AUTH),
        ),
        CredentialPreset(
            name = CredentialNames.IMPORTER_TDN,
            kind = CredentialKind.TIDAL_DEVICE_SESSION,
            description = "Tidal session for the tidal-dl-ng importer, refreshed on every hand-out",
            format = TidalSessionFormat.TDN,
            fileRoles = listOf(CredentialFileRoles.TDN_TOKEN),
        ),
        CredentialPreset(
            name = CredentialNames.IMPORTER_GAMDL,
            kind = CredentialKind.FILE,
            description = "Apple Music cookies and Widevine device file for the gamdl importer",
            fileRoles = listOf(CredentialFileRoles.GAMDL_COOKIES, CredentialFileRoles.GAMDL_WVD),
        ),
    )
}
