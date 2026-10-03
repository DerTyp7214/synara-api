@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core.wire

import dev.dertyp.data.Album
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.AudioInfo
import dev.dertyp.data.CollectionSongMatch
import dev.dertyp.data.Genre
import dev.dertyp.data.LikeLevel
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PlaybackReport
import dev.dertyp.data.PlaybackState
import dev.dertyp.data.QueueInfo
import dev.dertyp.data.QueueItem
import dev.dertyp.data.QueueWriteResult
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.data.UserSong
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.plus
import java.time.LocalDate
import java.util.Date
import java.util.UUID
import dev.dertyp.core.wire.old.Album as OldAlbum
import dev.dertyp.core.wire.old.Artist as OldArtist
import dev.dertyp.core.wire.old.ArtistCredit as OldArtistCredit
import dev.dertyp.core.wire.old.CollectionSongMatch as OldCollectionSongMatch
import dev.dertyp.core.wire.old.PlaybackReport as OldPlaybackReport
import dev.dertyp.core.wire.old.PlaybackState as OldPlaybackState
import dev.dertyp.core.wire.old.QueueInfo as OldQueueInfo
import dev.dertyp.core.wire.old.QueueItem as OldQueueItem
import dev.dertyp.core.wire.old.QueueWriteResult as OldQueueWriteResult
import dev.dertyp.core.wire.old.UserSong as OldUserSong

private val OldClientModule = SerializersModule {
    contextual(OldArtist.serializer())
    contextual(OldArtistCredit.serializer())
    contextual(OldAlbum.serializer())
}

val OldClientJson = Json(AppJson) { serializersModule = AppJson.serializersModule + OldClientModule }

val OldClientCbor = Cbor(AppCbor) { serializersModule = AppCbor.serializersModule + OldClientModule }

private fun uuid(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

private val genre = Genre(uuid(1), "Ambient")
private val audio = AudioInfo("flac", 44100, 16, 900, 1000, 2)
private val tags = listOf(TitleTag(TitleTagKind.LIVE, "Live"))

private val member = ArtistCredit(id = uuid(2), name = "Member", isGroup = false, musicBrainzId = uuid(3))
private val credit = ArtistCredit(
    id = uuid(4),
    name = "Band",
    isGroup = true,
    artists = listOf(member),
    genres = listOf(genre),
    musicBrainzId = uuid(5),
    creditedName = "The Band",
    joinPhrase = " & ",
)
private val album = Album(
    id = uuid(6),
    name = "Album",
    artists = listOf(credit),
    songCount = 2,
    releaseDate = LocalDate.of(2020, 1, 2),
    totalDuration = 1000,
    genres = listOf(genre),
    musicBrainzId = uuid(7),
)

val newSong = UserSong(
    id = uuid(8),
    title = "Song",
    artists = listOf(credit, member),
    album = album,
    duration = 200,
    explicit = true,
    path = "/a.flac",
    audio = audio,
    musicBrainzId = uuid(9),
    genres = listOf(genre),
    tags = tags,
    isFavourite = true,
    userSongCreatedAt = Date(1000),
    userSongUpdatedAt = Date(2000),
    likeLevel = LikeLevel.LIKE,
)

private val bareSong = UserSong(
    id = uuid(10),
    title = "Bare",
    artists = emptyList(),
    album = null,
    duration = 100,
    explicit = false,
    path = "/b.flac",
    userSongCreatedAt = Date(3000),
    userSongUpdatedAt = null,
)

private val oldMember = OldArtistCredit(id = uuid(2), name = "Member", isGroup = false, musicbrainzId = uuid(3))
private val oldCredit = OldArtistCredit(
    id = uuid(4),
    name = "Band",
    isGroup = true,
    artists = listOf(oldMember),
    genres = listOf(genre),
    musicbrainzId = uuid(5),
    creditedName = "The Band",
    joinPhrase = " & ",
)
private val oldAlbum = OldAlbum(
    id = uuid(6),
    name = "Album",
    artists = listOf(oldCredit),
    songCount = 2,
    releaseDate = LocalDate.of(2020, 1, 2),
    totalDuration = 1000,
    genres = listOf(genre),
    musicbrainzId = uuid(7),
)

val oldSong = OldUserSong(
    id = uuid(8),
    title = "Song",
    artists = listOf(oldCredit, oldMember),
    album = oldAlbum,
    duration = 200,
    explicit = true,
    path = "/a.flac",
    audio = audio,
    musicBrainzId = uuid(9),
    genres = listOf(genre),
    tags = tags,
    isFavourite = true,
    userSongCreatedAt = Date(1000),
    userSongUpdatedAt = Date(2000),
    likeLevel = LikeLevel.LIKE,
)

private val oldBareSong = OldUserSong(
    id = uuid(10),
    title = "Bare",
    artists = emptyList(),
    album = null,
    duration = 100,
    explicit = false,
    path = "/b.flac",
    userSongCreatedAt = Date(3000),
    userSongUpdatedAt = null,
)

val newState = PlaybackState(
    queue = listOf(
        PlaybackState.QueueEntry.WithSong(newSong, 1),
        PlaybackState.QueueEntry.FromSource(uuid(11), 2),
        PlaybackState.QueueEntry.WithSong(bareSong, 3),
    ),
    currentIndex = 0,
    isPlaying = true,
    positionMs = 1234,
    isShuffled = true,
    repeatMode = RepeatMode.ALL,
    sourceId = "playlist",
)

val oldState = OldPlaybackState(
    queue = listOf(
        OldPlaybackState.QueueEntry.Explicit(oldSong, 1),
        OldPlaybackState.QueueEntry.FromSource(uuid(11), 2),
        OldPlaybackState.QueueEntry.Explicit(oldBareSong, 3),
    ),
    currentIndex = 0,
    isPlaying = true,
    positionMs = 1234,
    shuffleMode = true,
    repeatMode = RepeatMode.ALL,
    sourceId = "playlist",
)

val newReport = PlaybackReport(songId = uuid(8), positionMs = 55, isPlaying = false, sentAt = 99)

val oldReport = OldPlaybackReport(songId = uuid(8), positionMs = 55, playing = false, sentAt = 99)

val newInfo =
    QueueInfo(version = 3, modifiedAt = 4, currentIndex = 1, isShuffled = true, repeatMode = RepeatMode.ONE, total = 2)

val oldInfo = OldQueueInfo(
    version = 3,
    modifiedAt = 4,
    currentIndex = 1,
    shuffleMode = true,
    repeatMode = RepeatMode.ONE,
    total = 2
)

val newWriteResults: List<QueueWriteResult> = listOf(QueueWriteResult.Ok(newInfo), QueueWriteResult.Conflict(newInfo))

val oldWriteResults: List<OldQueueWriteResult> =
    listOf(OldQueueWriteResult.Ok(oldInfo), OldQueueWriteResult.Conflict(oldInfo))

val newItems = PaginatedResponse(
    data = listOf(
        QueueItem(songId = uuid(8), queueId = 1, position = 0, userAdded = true, song = newSong),
        QueueItem(songId = uuid(10), queueId = 2, position = 1, shuffledPosition = 0),
    ),
    total = 2,
    pageSize = 50,
)

val oldItems = PaginatedResponse(
    data = listOf(
        OldQueueItem(songId = uuid(8), queueId = 1, position = 0, explicit = true, song = oldSong),
        OldQueueItem(songId = uuid(10), queueId = 2, position = 1, shuffledPosition = 0),
    ),
    total = 2,
    pageSize = 50,
)

val newMatches = PaginatedResponse(
    data = listOf(
        CollectionSongMatch(newSong, directMember = true),
        CollectionSongMatch(bareSong, directMember = false)
    ),
    total = 2,
    hasNextPage = true,
)

val oldMatches = PaginatedResponse(
    data = listOf(
        OldCollectionSongMatch(oldSong, explicitMember = true),
        OldCollectionSongMatch(oldBareSong, explicitMember = false)
    ),
    total = 2,
    hasNextPage = true,
)

class WireCase<N, O>(
    val name: String,
    val serializer: KSerializer<N>,
    val value: N,
    val oldSerializer: KSerializer<O>,
    val oldValue: O,
)

val wireCases: List<WireCase<*, *>> = listOf(
    WireCase("UserSong", UserSong.serializer(), newSong, OldUserSong.serializer(), oldSong),
    WireCase("PlaybackState", PlaybackState.serializer(), newState, OldPlaybackState.serializer(), oldState),
    WireCase(
        "nullable PlaybackState",
        PlaybackState.serializer().nullable,
        newState,
        OldPlaybackState.serializer().nullable,
        oldState,
    ),
    WireCase("PlaybackReport", PlaybackReport.serializer(), newReport, OldPlaybackReport.serializer(), oldReport),
    WireCase(
        "QueueEntry",
        PlaybackState.QueueEntry.serializer(),
        newState.queue.first(),
        OldPlaybackState.QueueEntry.serializer(),
        oldState.queue.first(),
    ),
    WireCase(
        "list of QueueEntry",
        ListSerializer(PlaybackState.QueueEntry.serializer()),
        newState.queue,
        ListSerializer(OldPlaybackState.QueueEntry.serializer()),
        oldState.queue,
    ),
    WireCase("QueueInfo", QueueInfo.serializer(), newInfo, OldQueueInfo.serializer(), oldInfo),
    WireCase(
        "QueueWriteResult",
        QueueWriteResult.serializer(),
        newWriteResults.first(),
        OldQueueWriteResult.serializer(),
        oldWriteResults.first(),
    ),
    WireCase(
        "list of QueueWriteResult",
        ListSerializer(QueueWriteResult.serializer()),
        newWriteResults,
        ListSerializer(OldQueueWriteResult.serializer()),
        oldWriteResults,
    ),
    WireCase(
        "paginated QueueItem",
        PaginatedResponse.serializer(QueueItem.serializer()),
        newItems,
        PaginatedResponse.serializer(OldQueueItem.serializer()),
        oldItems,
    ),
    WireCase(
        "paginated CollectionSongMatch",
        PaginatedResponse.serializer(CollectionSongMatch.serializer()),
        newMatches,
        PaginatedResponse.serializer(OldCollectionSongMatch.serializer()),
        oldMatches,
    ),
)

fun ByteArray.latin1(): String = String(this, Charsets.ISO_8859_1)
