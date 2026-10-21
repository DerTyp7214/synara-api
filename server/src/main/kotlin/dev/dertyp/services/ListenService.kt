package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.core.ChangeNotifier
import dev.dertyp.data.ChangeTopic
import dev.dertyp.data.LinkedListenGroup
import dev.dertyp.data.LinkedListenGroupKey
import dev.dertyp.data.ListenGroupSource
import dev.dertyp.data.ListenedAlbum
import dev.dertyp.data.ListenedArtist
import dev.dertyp.data.ListenedSong
import dev.dertyp.db.*
import dev.dertyp.core.db.dbQuery
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
    companion object {
        const val SUPER_LIKE_SEED_BONUS = 2f
    }

    private val hooks by inject<HookBus>()
    private val songService by inject<SongService>()
    private val albumService by inject<AlbumService>()
    private val artistService by inject<ArtistService>()

    private val changeNotifier by inject<ChangeNotifier>()

    private val _listenChanges =
        MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val listenChanges: SharedFlow<Unit> = _listenChanges.asSharedFlow()

    suspend fun listensChanged(userIds: Collection<PlatformUUID>) {
        _listenChanges.tryEmit(Unit)
        if (userIds.isEmpty()) return
        val audience = dbQuery { userIds.flatMapTo(LinkedHashSet()) { listenOwnerIds(it, ListenScope.SHARED) } }
        audience.forEach { changeNotifier.notify(it, ChangeTopic.LISTENS) }
    }

    suspend fun ingestListenBrainz(listenBrainzUserId: PlatformUUID, listens: List<IncomingListen>): Int {
        if (listens.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val linkedUserIds = dbQuery {
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
            UserListenBrainzLinkTable
                .select(UserListenBrainzLinkTable.userId)
                .where { UserListenBrainzLinkTable.listenBrainzUserId eq listenBrainzUserId }
                .map { it[UserListenBrainzLinkTable.userId].value }
        }

        listensChanged(linkedUserIds)
        hooks.emit(HookEvent.ListenIngested(listenBrainzUserId, listens.size))
        return listens.size
    }

    suspend fun recentSeedWeights(userId: PlatformUUID, cutoff: Long): Map<PlatformUUID, Float> = dbQuery {
        val owner = listenOwnerPredicate(userId, ListenScope.RECOMMENDATIONS)

        val weights = HashMap<PlatformUUID, Float>()
        ListenTable.innerJoin(SongTable, { ListenTable.songId }, { SongTable.id })
            .select(ListenTable.songId, ListenTable.msPlayed, SongTable.duration)
            .where { owner }
            .andWhere { ListenTable.listenedAt greater cutoff }
            .forEach { row ->
                val songId = row[ListenTable.songId]?.value ?: return@forEach
                val weight = ListenTable.playWeight(row[ListenTable.msPlayed], row[SongTable.duration])
                if (weight > 0f) weights.merge(songId, weight, Float::plus)
            }

        if (weights.isNotEmpty()) {
            UserSongTable.select(UserSongTable.songId)
                .where { UserSongTable.userId eq userId }
                .andWhere { UserSongTable.superLikedAt.isNotNull() }
                .forEach { weights.merge(it[UserSongTable.songId].value, SUPER_LIKE_SEED_BONUS, Float::plus) }
            weights
        } else {
            UserSongTable.select(UserSongTable.songId, UserSongTable.superLikedAt)
                .where { UserSongTable.userId eq userId }
                .andWhere { UserSongTable.isFavourite eq true }
                .associate {
                    val bonus = if (it[UserSongTable.superLikedAt] != null) SUPER_LIKE_SEED_BONUS else 0f
                    it[UserSongTable.songId].value to 1f + bonus
                }
        }
    }

    suspend fun ingestLocal(userId: PlatformUUID, songId: PlatformUUID, listenedAtMs: Long, msPlayed: Long?) {
        dbQuery {
            val meta = localListenMetadata(songId)
            val redirect = ListenSongRedirectTable
                .select(ListenSongRedirectTable.toSongId)
                .where { ListenSongRedirectTable.userId eq userId }
                .andWhere { ListenSongRedirectTable.fromSongId eq songId }
                .singleOrNull()?.get(ListenSongRedirectTable.toSongId)?.value
            ListenTable.insert {
                it[ListenTable.userId] = userId
                it[ListenTable.songId] = redirect ?: songId
                it[ListenTable.playedSongId] = redirect?.let { songId }
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
        listensChanged(listOf(userId))
    }

    private fun localListenMetadata(songId: PlatformUUID): LocalListenMetadata {
        val song = SongTable
            .select(SongTable.title, SongTable.titleTags, SongTable.albumId, SongTable.isrc)
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
            .select(AlbumTable.name, AlbumTable.titleTags)
            .where { AlbumTable.id eq albumId }
            .singleOrNull()?.fullAlbumName()?.ifBlank { null }

        val artistRows = SongArtistTable
            .join(ArtistTable, JoinType.INNER, onColumn = SongArtistTable.artistId, otherColumn = ArtistTable.id)
            .join(
                ArtistMusicBrainzTable,
                JoinType.LEFT,
                onColumn = SongArtistTable.artistId,
                otherColumn = ArtistMusicBrainzTable.artistId
            )
            .select(ArtistTable.name, ArtistMusicBrainzTable.musicBrainzId)
            .where { SongArtistTable.songId eq songId }
            .orderBy(SongArtistTable.position to SortOrder.ASC, SongArtistTable.artistId to SortOrder.ASC)
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
            trackName = song.fullSongTitle().ifBlank { null },
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

            val owner = listenOwnerPredicate(userId, ListenScope.OWN)

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

            val oldSongIds = replaceOverride(userId, songId, mbid, recordingMsid)

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

        if (result.linkedListens > 0) listensChanged(listOf(userId))
        return result
    }

    private fun replaceOverride(
        userId: PlatformUUID,
        songId: PlatformUUID,
        recordingMbid: PlatformUUID?,
        recordingMsid: PlatformUUID?,
    ): Set<PlatformUUID> {
        val existingOverrides = ListenLinkTable
            .selectAll()
            .where { ListenLinkTable.userId eq userId }
            .andWhere {
                listOfNotNull(
                    recordingMbid?.let { ListenLinkTable.recordingMbid eq it },
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
            it[ListenLinkTable.recordingMbid] = recordingMbid
            it[ListenLinkTable.recordingMsid] = recordingMsid
            it[createdAt] = System.currentTimeMillis()
        }
        return oldSongIds
    }

    suspend fun linkedGroups(userId: PlatformUUID, songId: PlatformUUID): List<LinkedListenGroup> =
        dbQuery { linkedGroupsOf(userId, songId) }

    private fun linkedGroupsOf(userId: PlatformUUID, songId: PlatformUUID): List<LinkedListenGroup> {
        val duration = SongTable
            .select(SongTable.duration)
            .where { SongTable.id eq songId }
            .singleOrNull()?.get(SongTable.duration)

        val rows = ListenTable
            .select(
                ListenTable.listenSource,
                ListenTable.playedSongId,
                ListenTable.recordingMbid,
                ListenTable.recordingMsid,
                ListenTable.trackName,
                ListenTable.artistName,
                ListenTable.releaseName,
                ListenTable.listenedAt,
                ListenTable.msPlayed,
            )
            .where { listenOwnerPredicate(userId, ListenScope.OWN) }
            .andWhere { ListenTable.songId eq songId }
            .toList()
        if (rows.isEmpty()) return emptyList()

        val redirectedFrom = ListenSongRedirectTable
            .select(ListenSongRedirectTable.fromSongId)
            .where { ListenSongRedirectTable.userId eq userId }
            .andWhere { ListenSongRedirectTable.toSongId eq songId }
            .map { it[ListenSongRedirectTable.fromSongId].value }
            .toSet()

        val overrides = ListenLinkTable
            .select(ListenLinkTable.recordingMbid, ListenLinkTable.recordingMsid)
            .where { ListenLinkTable.userId eq userId }
            .andWhere { ListenLinkTable.songId eq songId }
            .map { it[ListenLinkTable.recordingMbid] to it[ListenLinkTable.recordingMsid] }
        val overrideMbids = overrides.mapNotNull { it.first }.toSet()
        val overrideMsids = overrides.mapNotNull { it.second }.toSet()

        return rows
            .groupBy { row ->
                if (row[ListenTable.listenSource] == ListenSource.LOCAL) {
                    LinkedListenGroupKey(
                        source = ListenGroupSource.LOCAL,
                        playedSongId = row[ListenTable.playedSongId]?.value ?: songId,
                    )
                } else {
                    val mbid = row[ListenTable.recordingMbid]
                    LinkedListenGroupKey(
                        source = ListenGroupSource.LISTENBRAINZ,
                        recordingMbid = mbid,
                        recordingMsid = if (mbid == null) row[ListenTable.recordingMsid] else null,
                    )
                }
            }
            .map { (key, groupRows) ->
                val newest = groupRows.maxBy { it[ListenTable.listenedAt] }
                val mbid = key.recordingMbid
                val msid = key.recordingMsid
                val linkedManually = when {
                    key.source == ListenGroupSource.LOCAL -> key.playedSongId in redirectedFrom
                    mbid != null -> mbid in overrideMbids || groupRows.any { it[ListenTable.recordingMsid] in overrideMsids }
                    msid != null -> msid in overrideMsids
                    else -> false
                }
                LinkedListenGroup(
                    key = key,
                    trackName = newest[ListenTable.trackName],
                    artistName = newest[ListenTable.artistName],
                    releaseName = newest[ListenTable.releaseName],
                    listenCount = groupRows.size.toLong(),
                    listenedMs = groupRows.sumOf { ListenTable.playedMs(it[ListenTable.msPlayed], duration) },
                    lastListenedAt = newest[ListenTable.listenedAt],
                    linkedManually = linkedManually,
                )
            }
            .sortedWith(compareByDescending<LinkedListenGroup> { it.listenCount }.thenByDescending { it.lastListenedAt })
    }

    suspend fun relinkSong(
        userId: PlatformUUID,
        fromSongId: PlatformUUID,
        toSongId: PlatformUUID,
        groups: List<LinkedListenGroupKey>?,
    ): LinkUnmatchedResult {
        require(fromSongId != toSongId) { "fromSongId and toSongId must differ" }

        val result = dbQuery {
            require(SongTable.select(SongTable.id).where { SongTable.id eq toSongId }.any()) { "Song $toSongId not found" }

            val keys = (groups ?: linkedGroupsOf(userId, fromSongId).map { it.key }).distinct()
            val owner = listenOwnerPredicate(userId, ListenScope.OWN)
            val now = System.currentTimeMillis()
            var moved = 0
            val msids = LinkedHashSet<PlatformUUID>()

            for (key in keys) {
                when (key.source) {
                    ListenGroupSource.LOCAL -> {
                        val played = requireNotNull(key.playedSongId) { "playedSongId is required for LOCAL groups" }
                        val ids = ListenTable
                            .select(ListenTable.id)
                            .where { ListenTable.userId eq userId }
                            .andWhere { ListenTable.listenSource eq ListenSource.LOCAL }
                            .andWhere { ListenTable.songId eq fromSongId }
                            .andWhere {
                                if (played == fromSongId) {
                                    ListenTable.playedSongId.isNull() or (ListenTable.playedSongId eq played)
                                } else {
                                    ListenTable.playedSongId eq played
                                }
                            }
                            .map { it[ListenTable.id].value }
                        if (ids.isEmpty()) continue

                        val playedSongId = played.takeIf { it != toSongId }
                        ids.chunked(1000).forEach { chunk ->
                            moved += ListenTable.update({ ListenTable.id inList chunk }) {
                                it[ListenTable.songId] = toSongId
                                it[ListenTable.playedSongId] = playedSongId
                                it[ListenTable.updatedAt] = now
                            }
                        }

                        ListenSongRedirectTable.deleteWhere {
                            (ListenSongRedirectTable.userId eq userId) and (ListenSongRedirectTable.fromSongId eq played)
                        }
                        if (playedSongId != null) {
                            ListenSongRedirectTable.insert {
                                it[ListenSongRedirectTable.userId] = userId
                                it[ListenSongRedirectTable.fromSongId] = playedSongId
                                it[ListenSongRedirectTable.toSongId] = toSongId
                                it[createdAt] = now
                            }
                        }
                    }

                    ListenGroupSource.LISTENBRAINZ -> {
                        val mbid = key.recordingMbid
                        val msid = key.recordingMsid
                        val query = ListenTable
                            .select(ListenTable.id, ListenTable.recordingMsid)
                            .where { owner }
                            .andWhere { ListenTable.listenSource eq ListenSource.LISTENBRAINZ }
                            .andWhere { ListenTable.songId eq fromSongId }
                        when {
                            mbid != null -> query.andWhere { ListenTable.recordingMbid eq mbid }
                            msid != null -> query
                                .andWhere { ListenTable.recordingMbid.isNull() }
                                .andWhere { ListenTable.recordingMsid eq msid }
                            else -> query
                                .andWhere { ListenTable.recordingMbid.isNull() }
                                .andWhere { ListenTable.recordingMsid.isNull() }
                        }
                        val rows = query.toList()
                        if (rows.isEmpty()) continue

                        rows.map { it[ListenTable.id].value }.chunked(1000).forEach { chunk ->
                            moved += ListenTable.update({ ListenTable.id inList chunk }) {
                                it[ListenTable.songId] = toSongId
                                it[ListenTable.updatedAt] = now
                            }
                        }

                        val rowMsids = rows.mapNotNull { it[ListenTable.recordingMsid] }.distinct()
                        msids += rowMsids
                        if (rowMsids.isNotEmpty()) {
                            ListenLinkTable.deleteWhere {
                                (ListenLinkTable.userId eq userId) and (ListenLinkTable.recordingMsid inList rowMsids)
                            }
                        }
                        if (mbid != null || msid != null) {
                            replaceOverride(userId, toSongId, mbid, msid ?: rowMsids.firstOrNull())
                        }
                    }
                }
            }

            LinkUnmatchedResult(linkedListens = moved, recordingMsids = msids.toList())
        }

        if (result.linkedListens > 0) listensChanged(listOf(userId))
        return result
    }

    suspend fun recentListens(userId: PlatformUUID, limit: Int): List<ListenedSong> {
        val capped = limit.coerceIn(1, 1000)

        val rows = dbQuery {
            val owner = listenOwnerPredicate(userId, ListenScope.SHARED)

            ListenTable
                .select(
                    ListenTable.songId,
                    ListenTable.listenedAt,
                    ListenTable.recordingMbid,
                    ListenTable.isrcs,
                    ListenTable.listenSource
                )
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
                        source = it[ListenTable.listenSource],
                    )
                }
        }
        if (rows.isEmpty()) return emptyList()

        val kept = ArrayList<ListenRow>()
        for (row in rows) {
            val index = kept.indexOfFirst { samePlay(it, row) }
            when {
                index < 0 -> kept.add(row)
                row.source == ListenSource.LOCAL && kept[index].source != ListenSource.LOCAL -> kept[index] = row
            }
            if (kept.size >= capped) break
        }

        val songs = songService.byIds(kept.map { it.songId }.distinct(), userId).associateBy { it.id }
        return kept.mapNotNull { row ->
            songs[row.songId]?.let {
                ListenedSong(
                    song = it,
                    listenedAt = row.listenedAt
                )
            }
        }
    }

    suspend fun recentArtists(userId: PlatformUUID, limit: Int): List<ListenedArtist> {
        val capped = limit.coerceIn(1, 1000)

        val lastListened = dbQuery {
            val lastListen = ListenTable.listenedAt.max().alias("lastListen")
            ListenTable
                .join(
                    SongArtistTable,
                    JoinType.INNER,
                    onColumn = ListenTable.songId,
                    otherColumn = SongArtistTable.songId
                )
                .select(SongArtistTable.artistId, lastListen)
                .where { listenOwnerPredicate(userId, ListenScope.SHARED) }
                .andWhere { ListenTable.songId.isNotNull() }
                .groupBy(SongArtistTable.artistId)
                .orderBy(lastListen, SortOrder.DESC)
                .limit(capped)
                .associate { it[SongArtistTable.artistId].value to it[lastListen]!! }
        }
        if (lastListened.isEmpty()) return emptyList()

        return artistService.byIds(lastListened.keys.toList(), userId)
            .map { ListenedArtist(artist = it, lastListenedAt = lastListened.getValue(it.id)) }
    }

    suspend fun recentAlbums(userId: PlatformUUID, limit: Int): List<ListenedAlbum> {
        val capped = limit.coerceIn(1, 1000)

        val lastListened = dbQuery {
            val lastListen = ListenTable.listenedAt.max().alias("lastListen")
            ListenTable.innerJoin(SongTable, { ListenTable.songId }, { SongTable.id })
                .select(SongTable.albumId, lastListen)
                .where { listenOwnerPredicate(userId, ListenScope.SHARED) }
                .andWhere { ListenTable.songId.isNotNull() }
                .groupBy(SongTable.albumId)
                .orderBy(lastListen, SortOrder.DESC)
                .limit(capped)
                .associate { it[SongTable.albumId].value to it[lastListen]!! }
        }
        if (lastListened.isEmpty()) return emptyList()

        return albumService.byIds(lastListened.keys.toList(), userId)
            .map { ListenedAlbum(album = it, lastListenedAt = lastListened.getValue(it.id)) }
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
        val source: ListenSource,
    )

    private fun samePlay(a: ListenRow, b: ListenRow): Boolean {
        if (abs(a.listenedAt - b.listenedAt) > ListenTable.DEDUP_WINDOW_MS) return false
        if (a.songId == b.songId) return true
        if (a.recordingMbid != null && a.recordingMbid == b.recordingMbid) return true
        if (a.isrcs.any { it in b.isrcs }) return true
        return false
    }
}
