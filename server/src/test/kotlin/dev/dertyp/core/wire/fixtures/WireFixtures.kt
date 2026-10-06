@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core.wire.fixtures

import dev.dertyp.core.wire.LegacySealed
import dev.dertyp.core.wire.LegacySubclass
import dev.dertyp.core.wire.LegacyWire
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.RepeatMode
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import java.util.UUID
import kotlin.reflect.KClass

const val FIXTURE_RENAMES_API_VERSION = 8

val FixtureSealedClasses: List<KClass<*>> =
    listOf(WirePlaybackState.QueueEntry::class, WireQueueWriteResult::class, WireQueueUploadStart::class)

val FixtureWire = LegacyWire(
    listOf(
        LegacySealed(
            WirePlaybackState.QueueEntry::class,
            WirePlaybackState.QueueEntry.serializer(),
            listOf(
                LegacySubclass(
                    WirePlaybackState.QueueEntry.FromSource::class,
                    WirePlaybackState.QueueEntry.FromSource.serializer()
                ),
                LegacySubclass(
                    WirePlaybackState.QueueEntry.WithSong::class,
                    WirePlaybackState.QueueEntry.WithSong.serializer()
                ),
            ),
        ),
        LegacySealed(
            WireQueueWriteResult::class,
            WireQueueWriteResult.serializer(),
            listOf(
                LegacySubclass(WireQueueWriteResult.Ok::class, WireQueueWriteResult.Ok.serializer()),
                LegacySubclass(WireQueueWriteResult.Conflict::class, WireQueueWriteResult.Conflict.serializer()),
            ),
        ),
        LegacySealed(
            WireQueueUploadStart::class,
            WireQueueUploadStart.serializer(),
            listOf(
                LegacySubclass(WireQueueUploadStart.Started::class, WireQueueUploadStart.Started.serializer()),
                LegacySubclass(WireQueueUploadStart.Conflict::class, WireQueueUploadStart.Conflict.serializer()),
            ),
        ),
    ),
)

val FixtureLegacyJson = Json(AppJson) { serializersModule = AppJson.serializersModule + FixtureWire.module }

val FixtureLegacyCbor = Cbor(AppCbor) { serializersModule = AppCbor.serializersModule + FixtureWire.module }

private fun uuid(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

private val member = WireCredit(id = uuid(2), name = "Member", musicBrainzId = uuid(3))
private val credit = WireCredit(
    id = uuid(4),
    name = "Band",
    artists = listOf(member),
    musicBrainzId = uuid(5),
    joinPhrase = " & ",
)
private val album = WireAlbum(id = uuid(6), name = "Album", artists = listOf(credit), musicBrainzId = uuid(7))

val newSong = WireSong(
    id = uuid(8),
    title = "Song",
    artists = listOf(credit, member),
    album = album,
    duration = 200,
    musicBrainzId = uuid(9),
)

private val bareSong = WireSong(id = uuid(10), title = "Bare", artists = emptyList(), album = null, duration = 100)

private val oldMember = OldWireCredit(id = uuid(2), name = "Member", musicbrainzId = uuid(3))
private val oldCredit = OldWireCredit(
    id = uuid(4),
    name = "Band",
    artists = listOf(oldMember),
    musicbrainzId = uuid(5),
    joinPhrase = " & ",
)
private val oldAlbum =
    OldWireAlbum(id = uuid(6), name = "Album", artists = listOf(oldCredit), musicbrainzId = uuid(7))

val oldSong = OldWireSong(
    id = uuid(8),
    title = "Song",
    artists = listOf(oldCredit, oldMember),
    album = oldAlbum,
    duration = 200,
    musicBrainzId = uuid(9),
)

private val oldBareSong =
    OldWireSong(id = uuid(10), title = "Bare", artists = emptyList(), album = null, duration = 100)

val newState = WirePlaybackState(
    queue = listOf(
        WirePlaybackState.QueueEntry.WithSong(newSong, 1),
        WirePlaybackState.QueueEntry.FromSource(uuid(11), 2),
        WirePlaybackState.QueueEntry.WithSong(bareSong, 3),
    ),
    currentIndex = 0,
    isPlaying = true,
    positionMs = 1234,
    isShuffled = true,
    repeatMode = RepeatMode.ALL,
    sourceId = "playlist",
)

val oldState = OldWirePlaybackState(
    queue = listOf(
        OldWirePlaybackState.QueueEntry.Explicit(oldSong, 1),
        OldWirePlaybackState.QueueEntry.FromSource(uuid(11), 2),
        OldWirePlaybackState.QueueEntry.Explicit(oldBareSong, 3),
    ),
    currentIndex = 0,
    isPlaying = true,
    positionMs = 1234,
    shuffleMode = true,
    repeatMode = RepeatMode.ALL,
    sourceId = "playlist",
)

val newReport = WirePlaybackReport(songId = uuid(8), positionMs = 55, isPlaying = false, sentAt = 99)

val oldReport = OldWirePlaybackReport(songId = uuid(8), positionMs = 55, playing = false, sentAt = 99)

val newInfo = WireQueueInfo(
    version = 3,
    modifiedAt = 4,
    currentIndex = 1,
    isShuffled = true,
    repeatMode = RepeatMode.ONE,
    total = 2
)

val oldInfo = OldWireQueueInfo(
    version = 3,
    modifiedAt = 4,
    currentIndex = 1,
    shuffleMode = true,
    repeatMode = RepeatMode.ONE,
    total = 2
)

val newWriteResults: List<WireQueueWriteResult> =
    listOf(WireQueueWriteResult.Ok(newInfo), WireQueueWriteResult.Conflict(newInfo))

val oldWriteResults: List<OldWireQueueWriteResult> =
    listOf(OldWireQueueWriteResult.Ok(oldInfo), OldWireQueueWriteResult.Conflict(oldInfo))

val newItems = PaginatedResponse(
    data = listOf(
        WireQueueItem(songId = uuid(8), queueId = 1, position = 0, userAdded = true, song = newSong),
        WireQueueItem(songId = uuid(10), queueId = 2, position = 1, shuffledPosition = 0),
    ),
    total = 2,
    pageSize = 50,
)

val oldItems = PaginatedResponse(
    data = listOf(
        OldWireQueueItem(songId = uuid(8), queueId = 1, position = 0, explicit = true, song = oldSong),
        OldWireQueueItem(songId = uuid(10), queueId = 2, position = 1, shuffledPosition = 0),
    ),
    total = 2,
    pageSize = 50,
)

val newMatches = PaginatedResponse(
    data = listOf(
        WireSongMatch(newSong, directMember = true),
        WireSongMatch(bareSong, directMember = false)
    ),
    total = 2,
    hasNextPage = true,
)

val oldMatches = PaginatedResponse(
    data = listOf(
        OldWireSongMatch(oldSong, explicitMember = true),
        OldWireSongMatch(oldBareSong, explicitMember = false)
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
    WireCase("WireSong", WireSong.serializer(), newSong, OldWireSong.serializer(), oldSong),
    WireCase(
        "WirePlaybackState",
        WirePlaybackState.serializer(),
        newState,
        OldWirePlaybackState.serializer(),
        oldState
    ),
    WireCase(
        "nullable WirePlaybackState",
        WirePlaybackState.serializer().nullable,
        newState,
        OldWirePlaybackState.serializer().nullable,
        oldState,
    ),
    WireCase(
        "WirePlaybackReport",
        WirePlaybackReport.serializer(),
        newReport,
        OldWirePlaybackReport.serializer(),
        oldReport
    ),
    WireCase(
        "QueueEntry",
        WirePlaybackState.QueueEntry.serializer(),
        newState.queue.first(),
        OldWirePlaybackState.QueueEntry.serializer(),
        oldState.queue.first(),
    ),
    WireCase(
        "list of QueueEntry",
        ListSerializer(WirePlaybackState.QueueEntry.serializer()),
        newState.queue,
        ListSerializer(OldWirePlaybackState.QueueEntry.serializer()),
        oldState.queue,
    ),
    WireCase("WireQueueInfo", WireQueueInfo.serializer(), newInfo, OldWireQueueInfo.serializer(), oldInfo),
    WireCase(
        "WireQueueWriteResult",
        WireQueueWriteResult.serializer(),
        newWriteResults.first(),
        OldWireQueueWriteResult.serializer(),
        oldWriteResults.first(),
    ),
    WireCase(
        "list of WireQueueWriteResult",
        ListSerializer(WireQueueWriteResult.serializer()),
        newWriteResults,
        ListSerializer(OldWireQueueWriteResult.serializer()),
        oldWriteResults,
    ),
    WireCase(
        "paginated WireQueueItem",
        PaginatedResponse.serializer(WireQueueItem.serializer()),
        newItems,
        PaginatedResponse.serializer(OldWireQueueItem.serializer()),
        oldItems,
    ),
    WireCase(
        "paginated WireSongMatch",
        PaginatedResponse.serializer(WireSongMatch.serializer()),
        newMatches,
        PaginatedResponse.serializer(OldWireSongMatch.serializer()),
        oldMatches,
    ),
)

fun ByteArray.latin1(): String = String(this, Charsets.ISO_8859_1)
