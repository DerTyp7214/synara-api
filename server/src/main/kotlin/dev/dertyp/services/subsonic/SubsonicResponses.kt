package dev.dertyp.services.subsonic

import dev.dertyp.server.BuildConfig
import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue

const val SUBSONIC_API_VERSION = "1.16.1"
const val SUBSONIC_NS = "http://subsonic.org/restapi"

@Serializable
@XmlSerialName("subsonic-response")
data class SubsonicResponse(
    @XmlElement(false) val status: String = "ok",
    @XmlElement(false) val version: String = SUBSONIC_API_VERSION,
    @XmlElement(false) val type: String = "synara",
    @XmlElement(false) val serverVersion: String = BuildConfig.VERSION,
    @XmlElement(false) val openSubsonic: Boolean = true,
    @XmlElement(true) val error: SubsonicError? = null,
    @XmlElement(true) val license: License? = null,
    @XmlElement(true) val musicFolders: MusicFolders? = null,
    @XmlElement(true) @XmlSerialName("openSubsonicExtensions") val openSubsonicExtensions: List<OpenSubsonicExtension>? = null,
    @XmlElement(true) val artists: ArtistsID3? = null,
    @XmlElement(true) val artist: ArtistWithAlbumsID3? = null,
    @XmlElement(true) val album: AlbumWithSongsID3? = null,
    @XmlElement(true) val song: Child? = null,
    @XmlElement(true) val albumList2: AlbumList2? = null,
    @XmlElement(true) val genres: Genres? = null,
    @XmlElement(true) val songsByGenre: Songs? = null,
    @XmlElement(true) val randomSongs: Songs? = null,
    @XmlElement(true) val similarSongs2: Songs? = null,
    @XmlElement(true) val searchResult3: SearchResult3? = null,
    @XmlElement(true) val playlists: Playlists? = null,
    @XmlElement(true) val playlist: PlaylistWithSongs? = null,
    @XmlElement(true) val starred2: Starred2? = null,
    @XmlElement(true) val user: SubsonicUser? = null,
    @XmlElement(true) val lyrics: Lyrics? = null,
    @XmlElement(true) val lyricsList: LyricsList? = null,
    @XmlElement(true) val internetRadioStations: InternetRadioStations? = null,
    @XmlElement(true) val scanStatus: ScanStatus? = null,
)

@Serializable
@XmlSerialName("error")
data class SubsonicError(
    @XmlElement(false) val code: Int,
    @XmlElement(false) val message: String,
)

@Serializable
@XmlSerialName("license")
data class License(
    @XmlElement(false) val valid: Boolean = true,
    @XmlElement(false) val email: String? = null,
    @XmlElement(false) val licenseExpires: String? = null,
)

@Serializable
@XmlSerialName("musicFolders")
data class MusicFolders(
    @XmlElement(true) @XmlSerialName("musicFolder") val musicFolder: List<MusicFolder> = emptyList(),
)

@Serializable
@XmlSerialName("musicFolder")
data class MusicFolder(
    @XmlElement(false) val id: Int,
    @XmlElement(false) val name: String,
)

@Serializable
@XmlSerialName("openSubsonicExtensions")
data class OpenSubsonicExtension(
    @XmlElement(false) val name: String,
    @XmlElement(true) @XmlSerialName("versions") val versions: List<Int>,
)

@Serializable
@XmlSerialName("artists")
data class ArtistsID3(
    @XmlElement(false) val ignoredArticles: String = IGNORED_ARTICLES,
    @XmlElement(true) @XmlSerialName("index") val index: List<IndexID3> = emptyList(),
) {
    companion object {
        const val IGNORED_ARTICLES = "The El La Los Las Le Les"
    }
}

@Serializable
@XmlSerialName("index")
data class IndexID3(
    @XmlElement(false) val name: String,
    @XmlElement(true) @XmlSerialName("artist") val artist: List<ArtistID3> = emptyList(),
)

@Serializable
@XmlSerialName("artist")
data class ArtistID3(
    @XmlElement(false) val id: String,
    @XmlElement(false) val name: String,
    @XmlElement(false) val coverArt: String? = null,
    @XmlElement(false) val albumCount: Int? = null,
    @XmlElement(false) val starred: String? = null,
    @XmlElement(false) val musicBrainzId: String? = null,
)

@Serializable
@XmlSerialName("artist")
data class ArtistWithAlbumsID3(
    @XmlElement(false) val id: String,
    @XmlElement(false) val name: String,
    @XmlElement(false) val coverArt: String? = null,
    @XmlElement(false) val albumCount: Int? = null,
    @XmlElement(false) val starred: String? = null,
    @XmlElement(false) val musicBrainzId: String? = null,
    @XmlElement(true) @XmlSerialName("album") val album: List<AlbumID3> = emptyList(),
)

@Serializable
@XmlSerialName("album")
data class AlbumID3(
    @XmlElement(false) val id: String,
    @XmlElement(false) val name: String,
    @XmlElement(false) val artist: String? = null,
    @XmlElement(false) val artistId: String? = null,
    @XmlElement(false) val coverArt: String? = null,
    @XmlElement(false) val songCount: Int = 0,
    @XmlElement(false) val duration: Long = 0,
    @XmlElement(false) val created: String? = null,
    @XmlElement(false) val starred: String? = null,
    @XmlElement(false) val year: Int? = null,
    @XmlElement(false) val genre: String? = null,
    @XmlElement(false) val musicBrainzId: String? = null,
    @XmlElement(true) @XmlSerialName("genres") val genres: List<ItemGenre>? = null,
)

@Serializable
@XmlSerialName("album")
data class AlbumWithSongsID3(
    @XmlElement(false) val id: String,
    @XmlElement(false) val name: String,
    @XmlElement(false) val artist: String? = null,
    @XmlElement(false) val artistId: String? = null,
    @XmlElement(false) val coverArt: String? = null,
    @XmlElement(false) val songCount: Int = 0,
    @XmlElement(false) val duration: Long = 0,
    @XmlElement(false) val created: String? = null,
    @XmlElement(false) val starred: String? = null,
    @XmlElement(false) val year: Int? = null,
    @XmlElement(false) val genre: String? = null,
    @XmlElement(false) val musicBrainzId: String? = null,
    @XmlElement(true) @XmlSerialName("genres") val genres: List<ItemGenre>? = null,
    @XmlElement(true) @XmlSerialName("song") val song: List<Child> = emptyList(),
)

@Serializable
@XmlSerialName("genres")
data class ItemGenre(
    @XmlElement(false) val name: String,
)

@Serializable
@XmlSerialName("song")
data class Child(
    @XmlElement(false) val id: String,
    @XmlElement(false) val parent: String? = null,
    @XmlElement(false) val isDir: Boolean = false,
    @XmlElement(false) val title: String,
    @XmlElement(false) val album: String? = null,
    @XmlElement(false) val artist: String? = null,
    @XmlElement(false) val track: Int? = null,
    @XmlElement(false) val year: Int? = null,
    @XmlElement(false) val genre: String? = null,
    @XmlElement(false) val coverArt: String? = null,
    @XmlElement(false) val size: Long? = null,
    @XmlElement(false) val contentType: String? = null,
    @XmlElement(false) val suffix: String? = null,
    @XmlElement(false) val transcodedContentType: String? = null,
    @XmlElement(false) val transcodedSuffix: String? = null,
    @XmlElement(false) val duration: Long? = null,
    @XmlElement(false) val bitRate: Int? = null,
    @XmlElement(false) val samplingRate: Int? = null,
    @XmlElement(false) val bitDepth: Int? = null,
    @XmlElement(false) val discNumber: Int? = null,
    @XmlElement(false) val created: String? = null,
    @XmlElement(false) val starred: String? = null,
    @XmlElement(false) val albumId: String? = null,
    @XmlElement(false) val artistId: String? = null,
    @XmlElement(false) val type: String = "music",
    @XmlElement(false) val musicBrainzId: String? = null,
    @XmlElement(false) val sortName: String? = null,
    @XmlElement(true) @XmlSerialName("genres") val genres: List<ItemGenre>? = null,
)

@Serializable
@XmlSerialName("albumList2")
data class AlbumList2(
    @XmlElement(true) @XmlSerialName("album") val album: List<AlbumID3> = emptyList(),
)

@Serializable
@XmlSerialName("genres")
data class Genres(
    @XmlElement(true) @XmlSerialName("genre") val genre: List<GenreDto> = emptyList(),
)

@Serializable
@XmlSerialName("genre")
data class GenreDto(
    @XmlElement(false) val songCount: Int = 0,
    @XmlElement(false) val albumCount: Int = 0,
    @XmlValue val value: String,
)

@Serializable
data class Songs(
    @XmlElement(true) @XmlSerialName("song") val song: List<Child> = emptyList(),
)

@Serializable
@XmlSerialName("searchResult3")
data class SearchResult3(
    @XmlElement(true) @XmlSerialName("artist") val artist: List<ArtistID3> = emptyList(),
    @XmlElement(true) @XmlSerialName("album") val album: List<AlbumID3> = emptyList(),
    @XmlElement(true) @XmlSerialName("song") val song: List<Child> = emptyList(),
)

@Serializable
@XmlSerialName("playlists")
data class Playlists(
    @XmlElement(true) @XmlSerialName("playlist") val playlist: List<PlaylistDto> = emptyList(),
)

@Serializable
@XmlSerialName("playlist")
data class PlaylistDto(
    @XmlElement(false) val id: String,
    @XmlElement(false) val name: String,
    @XmlElement(false) val comment: String? = null,
    @XmlElement(false) val owner: String? = null,
    @XmlElement(false) val public: Boolean = false,
    @XmlElement(false) val songCount: Int = 0,
    @XmlElement(false) val duration: Long = 0,
    @XmlElement(false) val created: String? = null,
    @XmlElement(false) val changed: String? = null,
    @XmlElement(false) val coverArt: String? = null,
)

@Serializable
@XmlSerialName("playlist")
data class PlaylistWithSongs(
    @XmlElement(false) val id: String,
    @XmlElement(false) val name: String,
    @XmlElement(false) val comment: String? = null,
    @XmlElement(false) val owner: String? = null,
    @XmlElement(false) val public: Boolean = false,
    @XmlElement(false) val songCount: Int = 0,
    @XmlElement(false) val duration: Long = 0,
    @XmlElement(false) val created: String? = null,
    @XmlElement(false) val changed: String? = null,
    @XmlElement(false) val coverArt: String? = null,
    @XmlElement(true) @XmlSerialName("entry") val entry: List<Child> = emptyList(),
)

@Serializable
@XmlSerialName("starred2")
data class Starred2(
    @XmlElement(true) @XmlSerialName("artist") val artist: List<ArtistID3> = emptyList(),
    @XmlElement(true) @XmlSerialName("album") val album: List<AlbumID3> = emptyList(),
    @XmlElement(true) @XmlSerialName("song") val song: List<Child> = emptyList(),
)

@Serializable
@XmlSerialName("user")
data class SubsonicUser(
    @XmlElement(false) val username: String,
    @XmlElement(false) val scrobblingEnabled: Boolean = true,
    @XmlElement(false) val adminRole: Boolean = false,
    @XmlElement(false) val settingsRole: Boolean = false,
    @XmlElement(false) val downloadRole: Boolean = true,
    @XmlElement(false) val uploadRole: Boolean = false,
    @XmlElement(false) val playlistRole: Boolean = true,
    @XmlElement(false) val coverArtRole: Boolean = false,
    @XmlElement(false) val commentRole: Boolean = false,
    @XmlElement(false) val podcastRole: Boolean = false,
    @XmlElement(false) val streamRole: Boolean = true,
    @XmlElement(false) val jukeboxRole: Boolean = false,
    @XmlElement(false) val shareRole: Boolean = false,
    @XmlElement(false) val videoConversionRole: Boolean = false,
)

@Serializable
@XmlSerialName("lyrics")
data class Lyrics(
    @XmlElement(false) val artist: String? = null,
    @XmlElement(false) val title: String? = null,
    @XmlValue val value: String = "",
)

@Serializable
@XmlSerialName("lyricsList")
data class LyricsList(
    @XmlElement(true) @XmlSerialName("structuredLyrics") val structuredLyrics: List<StructuredLyrics> = emptyList(),
)

@Serializable
@XmlSerialName("structuredLyrics")
data class StructuredLyrics(
    @XmlElement(false) val displayArtist: String? = null,
    @XmlElement(false) val displayTitle: String? = null,
    @XmlElement(false) val lang: String = "und",
    @XmlElement(false) val synced: Boolean = false,
    @XmlElement(false) val offset: Long = 0,
    @XmlElement(true) @XmlSerialName("line") val line: List<LyricsLine> = emptyList(),
)

@Serializable
@XmlSerialName("line")
data class LyricsLine(
    @XmlElement(false) val start: Long? = null,
    @XmlValue val value: String,
)

@Serializable
@XmlSerialName("internetRadioStations")
data class InternetRadioStations(
    @XmlElement(true) @XmlSerialName("internetRadioStation") val internetRadioStation: List<InternetRadioStation> = emptyList(),
)

@Serializable
@XmlSerialName("internetRadioStation")
data class InternetRadioStation(
    @XmlElement(false) val id: String,
    @XmlElement(false) val name: String,
    @XmlElement(false) val streamUrl: String,
    @XmlElement(false) val homePageUrl: String? = null,
)

@Serializable
@XmlSerialName("scanStatus")
data class ScanStatus(
    @XmlElement(false) val scanning: Boolean = false,
    @XmlElement(false) val count: Long? = null,
)

@Serializable
data class SubsonicJsonEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse,
)

val SubsonicJson = Json {
    explicitNulls = false
    encodeDefaults = true
}

val SubsonicXml = XML {
    xmlDeclMode = XmlDeclMode.None
    indent = 0
}

fun SubsonicResponse.toXmlString(): String {
    val xml = SubsonicXml.encodeToString(SubsonicResponse.serializer(), this)
    val body = xml.replaceFirst("<subsonic-response", "<subsonic-response xmlns=\"$SUBSONIC_NS\"")
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n$body"
}

fun SubsonicResponse.toJsonString(): String =
    SubsonicJson.encodeToString(SubsonicJsonEnvelope.serializer(), SubsonicJsonEnvelope(this))

fun subsonicError(code: Int, message: String) =
    SubsonicResponse(status = "failed", error = SubsonicError(code, message))

suspend fun ApplicationCall.respondSubsonic(
    response: SubsonicResponse,
    format: String? = request.queryParameters["f"],
    callback: String? = request.queryParameters["callback"],
) {
    when (format?.lowercase()) {
        "json" -> respondText(response.toJsonString(), ContentType.Application.Json)
        "jsonp" -> respondText("${callback ?: "callback"}(${response.toJsonString()});", ContentType.Text.JavaScript)
        else -> respondText(response.toXmlString(), ContentType.Text.Xml.withCharset(Charsets.UTF_8))
    }
}
