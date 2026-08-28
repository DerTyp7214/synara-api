package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.ListenedSong
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import kotlin.math.abs

data class LinkUnmatchedResult(
    val linkedListens: Int,
    val recordingMsids: List<PlatformUUID>,
)

data class IncomingListen(
    val listenedAtMs: Long,
    val songId: PlatformUUID? = null,
    val recordingMbid: PlatformUUID? = null,
    val recordingMsid: PlatformUUID? = null,
    val releaseMbid: PlatformUUID? = null,
    val artistMbids: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val releaseName: String? = null,
    val isrcs: String? = null,
    val msPlayed: Long? = null,
)

class ListenService : Service() {
    private val hooks by inject<HookBus>()
    private val songService by inject<SongService>()

    private val _listenChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val listenChanges: SharedFlow<Unit> = _listenChanges.asSharedFlow()

    suspend fun ingestListenBrainz(listenBrainzUserId: PlatformUUID, listens: List<IncomingListen>): Int {
        if (listens.isEmpty()) return 0

        val now = System.currentTimeMillis()
        dbQuery {
            ListenTable.batchInsert(listens, ignore = true) { listen ->
                this[ListenTable.listenBrainzUserId] = listenBrainzUserId
                this[ListenTable.songId] = listen.songId
                this[ListenTable.recordingMbid] = listen.recordingMbid
                this[ListenTable.recordingMsid] = listen.recordingMsid
                this[ListenTable.releaseMbid] = listen.releaseMbid
                this[ListenTable.artistMbids] = listen.artistMbids
                this[ListenTable.trackName] = listen.trackName
                this[ListenTable.artistName] = listen.artistName
                this[ListenTable.releaseName] = listen.releaseName
                this[ListenTable.isrcs] = listen.isrcs
                this[ListenTable.listenedAt] = listen.listenedAtMs
                this[ListenTable.listenSource] = ListenSource.LISTENBRAINZ
                this[ListenTable.msPlayed] = listen.msPlayed
                this[ListenTable.updatedAt] = now
            }
        }

        _listenChanges.tryEmit(Unit)
        hooks.emit(HookEvent.ListenIngested(listenBrainzUserId, listens.size))
        return listens.size
    }

    suspend fun recentSeedWeights(userId: PlatformUUID, cutoff: Long): Map<PlatformUUID, Float> = dbQuery {
        val account = UserListenBrainzLinkTable
            .select(UserListenBrainzLinkTable.listenBrainzUserId)
            .where { UserListenBrainzLinkTable.userId eq userId }
            .firstOrNull()?.get(UserListenBrainzLinkTable.listenBrainzUserId)?.value

        val owner = if (account != null) {
            (ListenTable.userId eq userId) or (ListenTable.listenBrainzUserId eq account)
        } else {
            ListenTable.userId eq userId
        }

        val weights = HashMap<PlatformUUID, Float>()
        ListenTable.innerJoin(SongTable)
            .select(ListenTable.songId, ListenTable.msPlayed, SongTable.duration)
            .where { owner }
            .andWhere { ListenTable.listenedAt greater cutoff }
            .forEach { row ->
                val songId = row[ListenTable.songId]?.value ?: return@forEach
                val weight = ListenTable.playWeight(row[ListenTable.msPlayed], row[SongTable.duration])
                if (weight > 0f) weights.merge(songId, weight, Float::plus)
            }

        weights.ifEmpty {
            UserSongTable.select(UserSongTable.songId)
                .where { UserSongTable.userId eq userId }
                .andWhere { UserSongTable.isFavourite eq true }
                .associate { it[UserSongTable.songId].value to 1f }
        }
    }

    suspend fun ingestLocal(userId: PlatformUUID, songId: PlatformUUID, listenedAtMs: Long, msPlayed: Long?) {
        dbQuery {
            val meta = localListenMetadata(songId)
            ListenTable.insert {
                it[ListenTable.userId] = userId
                it[ListenTable.songId] = songId
                it[ListenTable.recordingMbid] = meta.recordingMbid
                it[ListenTable.releaseMbid] = meta.releaseMbid
                it[ListenTable.artistMbids] = meta.artistMbids
                it[ListenTable.trackName] = meta.trackName
                it[ListenTable.artistName] = meta.artistName
                it[ListenTable.releaseName] = meta.releaseName
                it[ListenTable.isrcs] = meta.isrcs
                it[ListenTable.listenedAt] = listenedAtMs
                it[ListenTable.listenSource] = ListenSource.LOCAL
                it[ListenTable.msPlayed] = msPlayed
                it[ListenTable.updatedAt] = System.currentTimeMillis()
            }
        }
        _listenChanges.tryEmit(Unit)
    }

    private fun localListenMetadata(songId: PlatformUUID): LocalListenMetadata {
        val song = SongTable
            .select(SongTable.title, SongTable.albumId, SongTable.isrc)
            .where { SongTable.id eq songId }
            .singleOrNull()
            ?: return LocalListenMetadata()

        val albumId = song[SongTable.albumId].value

        val recordingMbid = SongMusicBrainzTable
            .select(SongMusicBrainzTable.musicBrainzId)
            .where { SongMusicBrainzTable.songId eq songId }
            .singleOrNull()?.get(SongMusicBrainzTable.musicBrainzId)?.value

        val releaseMbid = AlbumMusicBrainzTable
            .select(AlbumMusicBrainzTable.musicBrainzId)
            .where { AlbumMusicBrainzTable.albumId eq albumId }
            .singleOrNull()?.get(AlbumMusicBrainzTable.musicBrainzId)?.value

        val releaseName = AlbumTable
            .select(AlbumTable.name)
            .where { AlbumTable.id eq albumId }
            .singleOrNull()?.get(AlbumTable.name)?.ifBlank { null }

        val artistRows = SongArtistTable
            .join(ArtistTable, JoinType.INNER, onColumn = SongArtistTable.artistId, otherColumn = ArtistTable.id)
            .join(ArtistMusicBrainzTable, JoinType.LEFT, onColumn = SongArtistTable.artistId, otherColumn = ArtistMusicBrainzTable.artistId)
            .select(ArtistTable.name, ArtistMusicBrainzTable.musicBrainzId)
            .where { SongArtistTable.songId eq songId }
            .orderBy(SongArtistTable.artistId)
            .toList()

        val artistName = artistRows.joinToString(", ") { it[ArtistTable.name] }.ifBlank { null }
        val artistMbids = artistRows
            .mapNotNull { it.getOrNull(ArtistMusicBrainzTable.musicBrainzId)?.value }
            .joinToString(",").ifBlank { null }

        val recordingIsrcs = recordingMbid?.let { mbid ->
            MBRecordingIsrcTable
                .select(MBRecordingIsrcTable.isrc)
                .where { MBRecordingIsrcTable.recordingId eq mbid }
                .map { it[MBRecordingIsrcTable.isrc] }
        } ?: emptyList()
        val isrcs = ListenTable.joinIsrcs(listOfNotNull(song[SongTable.isrc]) + recordingIsrcs)

        return LocalListenMetadata(
            isrcs = isrcs,
            recordingMbid = recordingMbid,
            releaseMbid = releaseMbid,
            artistMbids = artistMbids,
            trackName = song[SongTable.title].ifBlank { null },
            artistName = artistName,
            releaseName = releaseName,
        )
    }

    suspend fun linkUnmatched(
        userId: PlatformUUID,
        songId: PlatformUUID,
        recordingMsid: PlatformUUID?,
        recordingMbid: PlatformUUID?,
    ): LinkUnmatchedResult {
        require(recordingMsid != null || recordingMbid != null) { "recordingMsid or recordingMbid is required" }

        val result = dbQuery {
            require(SongTable.select(SongTable.id).where { SongTable.id eq songId }.any()) { "Song $songId not found" }

            val owner = ownerPredicate(userId)

            val mbid = recordingMbid ?: recordingMsid?.let { msid ->
                ListenTable
                    .select(ListenTable.recordingMbid)
                    .where { owner }
                    .andWhere { ListenTable.recordingMsid eq msid }
                    .andWhere { ListenTable.recordingMbid.isNotNull() }
                    .limit(1)
                    .firstOrNull()?.get(ListenTable.recordingMbid)
            }

            val identity = listOfNotNull(
                mbid?.let { ListenTable.recordingMbid eq it },
                recordingMsid?.let { ListenTable.recordingMsid eq it },
            ).reduce { a, b -> a or b }

            val existingOverrides = ListenLinkTable
                .selectAll()
                .where { ListenLinkTable.userId eq userId }
                .andWhere {
                    listOfNotNull(
                        mbid?.let { ListenLinkTable.recordingMbid eq it },
                        recordingMsid?.let { ListenLinkTable.recordingMsid eq it },
                    ).reduce { a, b -> a or b }
                }
                .toList()
            val oldSongIds = existingOverrides.map { it[ListenLinkTable.songId].value }.toSet()
            if (existingOverrides.isNotEmpty()) {
                ListenLinkTable.deleteWhere { ListenLinkTable.id inList existingOverrides.map { row -> row[ListenLinkTable.id].value } }
            }
            ListenLinkTable.insert {
                it[ListenLinkTable.userId] = userId
                it[ListenLinkTable.songId] = songId
                it[ListenLinkTable.recordingMbid] = mbid
                it[ListenLinkTable.recordingMsid] = recordingMsid
                it[createdAt] = System.currentTimeMillis()
            }

            val relinkable = if (oldSongIds.isEmpty()) {
                ListenTable.songId.isNull()
            } else {
                ListenTable.songId.isNull() or (ListenTable.songId inList oldSongIds)
            }

            val rows = ListenTable
                .select(ListenTable.id, ListenTable.recordingMsid)
                .where { owner }
                .andWhere { identity }
                .andWhere { relinkable }
                .toList()

            var updated = 0
            val now = System.currentTimeMillis()
            rows.map { it[ListenTable.id].value }.chunked(1000).forEach { chunk ->
                updated += ListenTable.update({ ListenTable.id inList chunk }) {
                    it[ListenTable.songId] = songId
                    it[ListenTable.updatedAt] = now
                }
            }

            val msids = (rows.mapNotNull { it[ListenTable.recordingMsid] } + listOfNotNull(recordingMsid)).distinct()
            LinkUnmatchedResult(linkedListens = updated, recordingMsids = msids)
        }

        if (result.linkedListens > 0) _listenChanges.tryEmit(Unit)
        return result
    }

    suspend fun recentListens(userId: PlatformUUID, limit: Int): List<ListenedSong> {
        val capped = limit.coerceIn(1, 1000)

        val rows = dbQuery {
            val owner = ownerPredicate(userId)

            ListenTable
                .select(ListenTable.songId, ListenTable.listenedAt, ListenTable.recordingMbid, ListenTable.isrcs)
                .where { owner }
                .andWhere { ListenTable.songId.isNotNull() }
                .orderBy(ListenTable.listenedAt to SortOrder.DESC)
                .limit((capped * 2).coerceAtMost(1000))
                .map {
                    ListenRow(
                        songId = it[ListenTable.songId]!!.value,
                        listenedAt = it[ListenTable.listenedAt],
                        recordingMbid = it[ListenTable.recordingMbid],
                        isrcs = ListenTable.parseIsrcs(it[ListenTable.isrcs]),
                    )
                }
        }
        if (rows.isEmpty()) return emptyList()

        val kept = ArrayList<ListenRow>()
        for (row in rows) {
            if (kept.none { samePlay(it, row) }) kept.add(row)
            if (kept.size >= capped) break
        }

        val songs = songService.byIds(kept.map { it.songId }.distinct(), userId).associateBy { it.id }
        return kept.mapNotNull { row -> songs[row.songId]?.let { ListenedSong(song = it, listenedAt = row.listenedAt) } }
    }

    private fun ownerPredicate(userId: PlatformUUID): Op<Boolean> {
        val lbId = UserListenBrainzLinkTable
            .select(UserListenBrainzLinkTable.listenBrainzUserId)
            .where { UserListenBrainzLinkTable.userId eq userId }
            .singleOrNull()?.get(UserListenBrainzLinkTable.listenBrainzUserId)?.value

        return if (lbId != null) {
            (ListenTable.userId eq userId) or (ListenTable.listenBrainzUserId eq lbId)
        } else {
            ListenTable.userId eq userId
        }
    }

    private data class LocalListenMetadata(
        val isrcs: String? = null,
        val recordingMbid: PlatformUUID? = null,
        val releaseMbid: PlatformUUID? = null,
        val artistMbids: String? = null,
        val trackName: String? = null,
        val artistName: String? = null,
        val releaseName: String? = null,
    )

    private data class ListenRow(
        val songId: PlatformUUID,
        val listenedAt: Long,
        val recordingMbid: PlatformUUID?,
        val isrcs: Set<String>,
    )

    private fun samePlay(a: ListenRow, b: ListenRow): Boolean {
        if (abs(a.listenedAt - b.listenedAt) > ListenTable.DEDUP_WINDOW_MS) return false
        if (a.songId == b.songId) return true
        if (a.recordingMbid != null && a.recordingMbid == b.recordingMbid) return true
        if (a.isrcs.any { it in b.isrcs }) return true
        return false
    }
}
