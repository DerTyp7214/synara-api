package dev.dertyp.services.metadata

import dev.dertyp.core.fetchBatchedResultsByIdKeyset
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.Service
import dev.dertyp.utils.parsers.ParserFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class MusicBrainzCacheService : Service() {

    suspend fun getStats(): ServerStats.MusicBrainzCacheStats = dbQuery {
        val staleSince = Clock.System.now().toEpochMilliseconds() - 90.days.inWholeMilliseconds
        ServerStats.MusicBrainzCacheStats(
            artistCount = MBArtistTable.selectAll().count().toInt(),
            staleArtistCount = MBArtistTable.selectAll().where { MBArtistTable.lastUpdate less staleSince }.count().toInt(),
            releaseGroupCount = MBReleaseGroupTable.selectAll().count().toInt(),
            staleReleaseGroupCount = MBReleaseGroupTable.selectAll().where { MBReleaseGroupTable.lastUpdate less staleSince }.count().toInt(),
            releaseCount = MBReleaseTable.selectAll().count().toInt(),
            staleReleaseCount = MBReleaseTable.selectAll().where { MBReleaseTable.lastUpdate less staleSince }.count().toInt(),
            recordingCount = MBRecordingTable.selectAll().count().toInt(),
            staleRecordingCount = MBRecordingTable.selectAll().where { MBRecordingTable.lastUpdate less staleSince }.count().toInt()
        )
    }


    fun staleArtistIdsFlow(staleSince: Long = Clock.System.now().toEpochMilliseconds() - 30.days.inWholeMilliseconds): Flow<UUID> = flow {
        MBArtistTable.select(MBArtistTable.id).where { MBArtistTable.lastUpdate less staleSince }
            .fetchBatchedResultsByIdKeyset(MBArtistTable.id, 100) { batch ->
                for (row in batch) {
                    emit(row[MBArtistTable.id].value)
                }
            }
    }

    fun staleReleaseGroupIdsFlow(staleSince: Long = Clock.System.now().toEpochMilliseconds() - 30.days.inWholeMilliseconds): Flow<UUID> = flow {
        MBReleaseGroupTable.select(MBReleaseGroupTable.id).where { MBReleaseGroupTable.lastUpdate less staleSince }
            .fetchBatchedResultsByIdKeyset(MBReleaseGroupTable.id, 100) { batch ->
                for (row in batch) {
                    emit(row[MBReleaseGroupTable.id].value)
                }
            }
    }

    fun staleReleaseIdsFlow(staleSince: Long = Clock.System.now().toEpochMilliseconds() - 30.days.inWholeMilliseconds): Flow<UUID> = flow {
        MBReleaseTable.select(MBReleaseTable.id).where { MBReleaseTable.lastUpdate less staleSince }
            .fetchBatchedResultsByIdKeyset(MBReleaseTable.id, 100) { batch ->
                for (row in batch) {
                    emit(row[MBReleaseTable.id].value)
                }
            }
    }

    fun staleRecordingIdsFlow(staleSince: Long = Clock.System.now().toEpochMilliseconds() - 30.days.inWholeMilliseconds): Flow<UUID> = flow {
        MBRecordingTable.select(MBRecordingTable.id).where { MBRecordingTable.lastUpdate less staleSince }
            .fetchBatchedResultsByIdKeyset(MBRecordingTable.id, 100) { batch ->
                for (row in batch) {
                    emit(row[MBRecordingTable.id].value)
                }
            }
    }

    suspend fun getArtist(id: UUID): MusicBrainzArtist? = dbQuery {
        MBArtistTable.selectAll().where { MBArtistTable.id eq id }.singleOrNull()?.let { row ->
            val area = row[MBArtistTable.area]?.let { getArea(it.value) }
            val beginArea = row[MBArtistTable.beginArea]?.let { getArea(it.value) }
            val aliases = MBArtistAliasTable.selectAll().where { MBArtistAliasTable.artistId eq id }.map { aliasRow ->
                MusicBrainzAlias(
                    name = aliasRow[MBArtistAliasTable.name],
                    sortName = aliasRow[MBArtistAliasTable.sortName],
                    locale = aliasRow[MBArtistAliasTable.locale],
                    type = aliasRow[MBArtistAliasTable.type],
                    primary = aliasRow[MBArtistAliasTable.primary],
                    beginDate = aliasRow[MBArtistAliasTable.beginDate],
                    endDate = aliasRow[MBArtistAliasTable.endDate]
                )
            }
            val tags = MBArtistTagTable.selectAll().where { MBArtistTagTable.artistId eq id }.map { tagRow ->
                MusicBrainzTag(
                    count = tagRow[MBArtistTagTable.count],
                    name = tagRow[MBArtistTagTable.name]
                )
            }

            MusicBrainzArtist(
                id = id,
                name = row[MBArtistTable.name],
                sortName = row[MBArtistTable.sortName],
                type = row[MBArtistTable.type]?.let { ArtistType.valueOf(it) },
                disambiguation = row[MBArtistTable.disambiguation],
                country = row[MBArtistTable.country],
                area = area,
                beginArea = beginArea,
                lifeSpan = MusicBrainzLifeSpan(
                    begin = row[MBArtistTable.lifeSpanBegin],
                    end = row[MBArtistTable.lifeSpanEnd],
                    ended = row[MBArtistTable.lifeSpanEnded]
                ),
                aliases = aliases,
                tags = tags,
                fetchedAt = row[MBArtistTable.lastUpdate]
            )
        }
    }

    suspend fun getArea(id: UUID): MusicBrainzArea? = dbQuery {
        MBAreaTable.selectAll().where { MBAreaTable.id eq id }.singleOrNull()?.let {
            MusicBrainzArea(
                id = id,
                name = it[MBAreaTable.name],
                sortName = it[MBAreaTable.sortName]
            )
        }
    }

    suspend fun getRecording(id: UUID): MusicBrainzRecording? = dbQuery {
        MBRecordingTable.selectAll().where { MBRecordingTable.id eq id }.singleOrNull()?.let { row ->
            mapRecording(id, row)
        }
    }

    suspend fun getRecordingByIsrc(isrc: String): MusicBrainzRecording? = dbQuery {
        MBRecordingIsrcTable
            .leftJoin(MBRecordingTable)
            .selectAll()
            .where { MBRecordingIsrcTable.isrc eq isrc }
            .singleOrNull()?.let { row ->
                val id = row[MBRecordingTable.id].value
                mapRecording(id, row)
            }
    }

    private fun mapRecording(id: UUID, row: ResultRow): MusicBrainzRecording {
        val artistCredits = MBRecordingArtistCreditTable
            .leftJoin(MBArtistTable)
            .selectAll()
            .where { MBRecordingArtistCreditTable.recordingId eq id }
            .orderBy(MBRecordingArtistCreditTable.position)
            .map { creditRow ->
                MusicBrainzArtistCredit(
                    name = creditRow[MBRecordingArtistCreditTable.name],
                    joinphrase = creditRow[MBRecordingArtistCreditTable.joinPhrase],
                    artist = MusicBrainzArtist(
                        id = creditRow[MBArtistTable.id].value,
                        name = creditRow[MBArtistTable.name],
                        sortName = creditRow[MBArtistTable.sortName]
                    )
                )
            }

        val releases = MBRecordingReleaseTable
            .leftJoin(MBReleaseTable)
            .selectAll()
            .where { MBRecordingReleaseTable.recordingId eq id }
            .map { releaseRow ->
                MusicBrainzRelease(
                    id = releaseRow[MBReleaseTable.id].value,
                    title = releaseRow[MBReleaseTable.title]
                )
            }

        val isrcs = MBRecordingIsrcTable
            .selectAll()
            .where { MBRecordingIsrcTable.recordingId eq id }
            .map { it[MBRecordingIsrcTable.isrc] }

        val relations = MBRelationTable
            .selectAll()
            .where { MBRelationTable.ownerId eq id }
            .map { relRow ->
                MusicBrainzRelation(
                    type = relRow[MBRelationTable.type],
                    url = MusicBrainzRelationUrl(
                        id = relRow[MBRelationTable.id],
                        resource = relRow[MBRelationTable.resource]
                    )
                )
            }

        return MusicBrainzRecording(
            id = id,
            title = row[MBRecordingTable.title],
            length = row[MBRecordingTable.length],
            artistCredit = artistCredits,
            releases = releases,
            isrcs = isrcs,
            relations = relations,
            fetchedAt = row[MBRecordingTable.lastUpdate]
        )
    }

    suspend fun getRelease(id: UUID): MusicBrainzRelease? = dbQuery {
        MBReleaseTable.selectAll().where { MBReleaseTable.id eq id }.singleOrNull()?.let { row ->
            val releaseGroup = row[MBReleaseTable.releaseGroupId]?.let { getReleaseGroup(it.value) }
            val artistCredits = MBReleaseArtistCreditTable
                .leftJoin(MBArtistTable)
                .selectAll()
                .where { MBReleaseArtistCreditTable.releaseId eq id }
                .orderBy(MBReleaseArtistCreditTable.position)
                .map { creditRow ->
                    MusicBrainzArtistCredit(
                        name = creditRow[MBReleaseArtistCreditTable.name],
                        joinphrase = creditRow[MBReleaseArtistCreditTable.joinPhrase],
                        artist = MusicBrainzArtist(
                            id = creditRow[MBArtistTable.id].value,
                            name = creditRow[MBArtistTable.name],
                            sortName = creditRow[MBArtistTable.sortName]
                        )
                    )
                }

            val media = MBMediaTable.selectAll().where { MBMediaTable.releaseId eq id }.orderBy(MBMediaTable.position).map { mediaRow ->
                val mediaId = mediaRow[MBMediaTable.id].value
                val tracks = MBTrackTable
                    .leftJoin(MBRecordingTable)
                    .selectAll()
                    .where { MBTrackTable.mediaId eq mediaId }
                    .orderBy(MBTrackTable.position)
                    .map { trackRow ->
                        val recordingId = trackRow[MBTrackTable.recordingId]?.value
                        val recording = if (recordingId != null) {
                            MusicBrainzRecording(
                                id = recordingId,
                                title = trackRow[MBRecordingTable.title]
                            )
                        } else null

                        MusicBrainzTrack(
                            id = trackRow[MBTrackTable.id].value,
                            position = trackRow[MBTrackTable.position],
                            number = trackRow[MBTrackTable.number],
                            title = trackRow[MBTrackTable.title],
                            recording = recording
                        )
                    }

                MusicBrainzMedia(
                    format = mediaRow[MBMediaTable.format],
                    trackCount = mediaRow[MBMediaTable.trackCount],
                    tracks = tracks
                )
            }

            val relations = MBRelationTable
                .selectAll()
                .where { MBRelationTable.ownerId eq id }
                .map { relRow ->
                    MusicBrainzRelation(
                        type = relRow[MBRelationTable.type],
                        url = MusicBrainzRelationUrl(
                            id = relRow[MBRelationTable.id],
                            resource = relRow[MBRelationTable.resource]
                        )
                    )
                }

            MusicBrainzRelease(
                id = id,
                title = row[MBReleaseTable.title],
                status = row[MBReleaseTable.status],
                quality = row[MBReleaseTable.quality],
                barcode = row[MBReleaseTable.barcode],
                country = row[MBReleaseTable.country],
                date = row[MBReleaseTable.date],
                disambiguation = row[MBReleaseTable.disambiguation],
                releaseGroup = releaseGroup,
                relations = relations,
                artistCredit = artistCredits,
                media = media,
                fetchedAt = row[MBReleaseTable.lastUpdate]
            )
        }
    }

    suspend fun getReleaseGroup(id: UUID): MusicBrainzReleaseGroup? = dbQuery {
        MBReleaseGroupTable.selectAll().where { MBReleaseGroupTable.id eq id }.singleOrNull()?.let { row ->
            val relations = MBRelationTable
                .selectAll()
                .where { MBRelationTable.ownerId eq id }
                .map { relRow ->
                    MusicBrainzRelation(
                        type = relRow[MBRelationTable.type],
                        url = MusicBrainzRelationUrl(
                            id = relRow[MBRelationTable.id],
                            resource = relRow[MBRelationTable.resource]
                        )
                    )
                }

            MusicBrainzReleaseGroup(
                id = id,
                title = row[MBReleaseGroupTable.title],
                primaryType = row[MBReleaseGroupTable.primaryType],
                firstReleaseDate = row[MBReleaseGroupTable.firstReleaseDate],
                relations = relations,
                fetchedAt = row[MBReleaseGroupTable.lastUpdate]
            )
        }
    }

    suspend fun getReleasesByReleaseGroup(releaseGroupId: UUID): List<MusicBrainzRelease> = dbQuery {
        MBReleaseTable.select(MBReleaseTable.id)
            .where { MBReleaseTable.releaseGroupId eq releaseGroupId }
            .mapNotNull { getRelease(it[MBReleaseTable.id].value) }
    }

    suspend fun updateArtistCache(artist: MusicBrainzArtist) = dbQuery {
        MBArtistTable.upsert(MBArtistTable.id) { b ->
            b[id] = artist.id
            b[name] = artist.name ?: ""
            b[sortName] = artist.sortName ?: artist.name ?: ""
            artist.type?.name?.let { b[type] = it }
            artist.disambiguation?.let { b[disambiguation] = it }
            artist.country?.let { b[country] = it }
            artist.lifeSpan?.begin?.let { b[lifeSpanBegin] = it }
            artist.lifeSpan?.end?.let { b[lifeSpanEnd] = it }
            artist.lifeSpan?.ended?.let { b[lifeSpanEnded] = it }
            b[lastUpdate] = Clock.System.now().toEpochMilliseconds()
        }

        artist.area?.let { area ->
            updateAreaCache(area)
            MBArtistTable.update({ MBArtistTable.id eq artist.id }) {
                it[MBArtistTable.area] = area.id
            }
        }

        artist.beginArea?.let { area ->
            updateAreaCache(area)
            MBArtistTable.update({ MBArtistTable.id eq artist.id }) {
                it[MBArtistTable.beginArea] = area.id
            }
        }

        artist.aliases?.let { aliases ->
            MBArtistAliasTable.deleteWhere { MBArtistAliasTable.artistId eq artist.id }
            aliases.forEach { alias ->
                MBArtistAliasTable.insert {
                    it[artistId] = artist.id
                    it[name] = alias.name
                    it[sortName] = alias.sortName
                    it[locale] = alias.locale
                    it[type] = alias.type
                    it[primary] = alias.primary ?: false
                    it[beginDate] = alias.beginDate
                    it[endDate] = alias.endDate
                }
            }
        }

        artist.tags?.let { tags ->
            MBArtistTagTable.deleteWhere { MBArtistTagTable.artistId eq artist.id }
            tags.forEach { tag ->
                MBArtistTagTable.upsert(MBArtistTagTable.artistId, MBArtistTagTable.name) {
                    it[artistId] = artist.id
                    it[name] = tag.name
                    it[count] = tag.count
                }
            }
        }
    }

    suspend fun updateAreaCache(area: MusicBrainzArea) = dbQuery {
        MBAreaTable.upsert(MBAreaTable.id) { b ->
            b[id] = area.id
            b[name] = area.name ?: ""
            b[sortName] = area.sortName ?: area.name ?: ""
        }
    }

    suspend fun updateRecordingCache(recording: MusicBrainzRecording) = dbQuery {
        MBRecordingTable.upsert(MBRecordingTable.id) { b ->
            b[id] = recording.id
            b[title] = recording.title ?: ""
            recording.length?.let { b[length] = it }
            b[lastUpdate] = Clock.System.now().toEpochMilliseconds()
        }

        MBRecordingArtistCreditTable.deleteWhere { MBRecordingArtistCreditTable.recordingId eq recording.id }
        recording.artistCredit?.forEachIndexed { index, credit ->
            credit.artist?.let { mbArtist ->
                updateArtistCache(mbArtist)

                MBRecordingArtistCreditTable.upsert(MBRecordingArtistCreditTable.recordingId, MBRecordingArtistCreditTable.artistId, MBRecordingArtistCreditTable.position) {
                    it[recordingId] = recording.id
                    it[artistId] = mbArtist.id
                    it[name] = credit.name ?: ""
                    it[joinPhrase] = credit.joinphrase
                    it[position] = index
                }
            }
        }

        recording.releases?.forEach { release ->
            updateReleaseCache(release)
            MBRecordingReleaseTable.upsert(MBRecordingReleaseTable.recordingId, MBRecordingReleaseTable.releaseId) {
                it[recordingId] = recording.id
                it[releaseId] = release.id
            }
        }

        MBRecordingIsrcTable.deleteWhere { MBRecordingIsrcTable.recordingId eq recording.id }
        recording.isrcs?.forEach { isrc ->
            MBRecordingIsrcTable.upsert(MBRecordingIsrcTable.recordingId, MBRecordingIsrcTable.isrc) {
                it[recordingId] = recording.id
                it[this.isrc] = isrc
            }
        }

        cacheRelations(recording.id, recording.relations)
    }

    suspend fun updateRecordingIsrcs(recordingId: UUID, isrcs: List<String>) = dbQuery {
        MBRecordingTable.update({ MBRecordingTable.id eq recordingId }) {
            it[lastUpdate] = Clock.System.now().toEpochMilliseconds()
        }
        MBRecordingIsrcTable.deleteWhere { MBRecordingIsrcTable.recordingId eq recordingId }
        isrcs.forEach { isrc ->
            MBRecordingIsrcTable.upsert(MBRecordingIsrcTable.recordingId, MBRecordingIsrcTable.isrc) {
                it[this.recordingId] = recordingId
                it[this.isrc] = isrc
            }
        }
    }

    suspend fun updateReleaseCache(release: MusicBrainzRelease): Unit = dbQuery {
        MBReleaseTable.upsert(MBReleaseTable.id) { b ->
            b[id] = release.id
            b[title] = release.title ?: ""
            release.status?.let { b[status] = it }
            release.quality?.let { b[quality] = it }
            release.barcode?.let { b[barcode] = it }
            release.country?.let { b[country] = it }
            release.date?.let { b[date] = it }
            release.disambiguation?.let { b[disambiguation] = it }
            b[lastUpdate] = Clock.System.now().toEpochMilliseconds()
        }

        release.releaseGroup?.let { group ->
            updateReleaseGroupCache(group)
            MBReleaseTable.update({ MBReleaseTable.id eq release.id }) {
                it[releaseGroupId] = group.id
            }
        }

        MBReleaseArtistCreditTable.deleteWhere { MBReleaseArtistCreditTable.releaseId eq release.id }
        release.artistCredit?.forEachIndexed { index, credit ->
            credit.artist?.let { mbArtist ->
                updateArtistCache(mbArtist)

                MBReleaseArtistCreditTable.upsert(MBReleaseArtistCreditTable.releaseId, MBReleaseArtistCreditTable.artistId, MBReleaseArtistCreditTable.position) {
                    it[releaseId] = release.id
                    it[artistId] = mbArtist.id
                    it[name] = credit.name ?: ""
                    it[joinPhrase] = credit.joinphrase
                    it[position] = index
                }
            }
        }

        MBMediaTable.deleteWhere { MBMediaTable.releaseId eq release.id }
        release.media?.forEachIndexed { index, media ->
            val mediaId = MBMediaTable.insertAndGetId {
                it[releaseId] = release.id
                it[position] = index
                it[format] = media.format
                it[trackCount] = media.trackCount ?: 0
            }

            media.tracks?.forEach { track ->
                track.recording?.let { recording ->
                    updateRecordingCache(recording)
                }

                MBTrackTable.upsert(MBTrackTable.id) { b ->
                    b[id] = track.id
                    b[MBTrackTable.mediaId] = mediaId
                    track.position?.let { b[position] = it }
                    track.number?.let { b[number] = it }
                    track.title?.let { b[title] = it }
                    track.recording?.id?.let { b[recordingId] = it }
                }
            }
        }

        cacheRelations(release.id, release.relations)
    }

    suspend fun updateReleaseGroupCache(group: MusicBrainzReleaseGroup) = dbQuery {
        MBReleaseGroupTable.upsert(MBReleaseGroupTable.id) { b ->
            b[id] = group.id
            b[title] = group.title
            group.primaryType?.let { b[primaryType] = it }
            group.firstReleaseDate?.let { b[firstReleaseDate] = it }
            b[lastUpdate] = Clock.System.now().toEpochMilliseconds()
        }

        cacheRelations(group.id, group.relations)
    }

    private suspend fun cacheRelations(mbId: UUID, relations: List<MusicBrainzRelation>?) {
        MBRelationTable.deleteWhere { MBRelationTable.ownerId eq mbId }
        MBRelationProviderTable.deleteWhere { MBRelationProviderTable.ownerId eq mbId }

        relations?.forEach { relation ->
            relation.url?.let { url ->
                MBRelationTable.upsert(MBRelationTable.id, MBRelationTable.ownerId) {
                    it[id] = url.id
                    it[ownerId] = mbId
                    it[type] = relation.type ?: ""
                    it[resource] = url.resource
                }

                val parser = ParserFactory.getParser(url.resource)
                val parsed = parser?.parse(url.resource)
                MBRelationProviderTable.upsert(
                    MBRelationProviderTable.ownerId,
                    MBRelationProviderTable.provider,
                    MBRelationProviderTable.externalId
                ) {
                    it[ownerId] = mbId
                    it[provider] = parser?.name ?: "unknown"
                    it[externalId] = parsed?.first ?: url.resource
                    it[type] = parsed?.second?.value
                    it[rawUrl] = url.resource
                }
            }
        }
    }

    suspend fun relationSiblingUrls(url: String): List<String> {
        val parser = ParserFactory.getParser(url) ?: return emptyList()
        val parsed = parser.parse(url)
        return dbQuery {
            val owners = if (parser.name == "musicbrainz" && parsed != null) {
                runCatching { listOf(UUID.fromString(parsed.first)) }.getOrDefault(emptyList())
            } else {
                MBRelationProviderTable.select(MBRelationProviderTable.ownerId)
                    .where {
                        (MBRelationProviderTable.rawUrl eq url) or
                            (if (parsed != null) {
                                (MBRelationProviderTable.provider eq parser.name) and
                                    (MBRelationProviderTable.externalId eq parsed.first)
                            } else Op.FALSE)
                    }
                    .map { it[MBRelationProviderTable.ownerId] }
                    .distinct()
            }

            if (owners.isEmpty()) emptyList()
            else MBRelationProviderTable.select(MBRelationProviderTable.rawUrl)
                .where { MBRelationProviderTable.ownerId inList owners }
                .map { it[MBRelationProviderTable.rawUrl] }
                .distinct()
        }
    }

    suspend fun updateArtistLastUpdate(id: UUID, lastUpdate: Long) = dbQuery {
        MBArtistTable.update({ MBArtistTable.id eq id }) {
            it[MBArtistTable.lastUpdate] = lastUpdate
        }
    }

    suspend fun updateReleaseGroupLastUpdate(id: UUID, lastUpdate: Long) = dbQuery {
        MBReleaseGroupTable.update({ MBReleaseGroupTable.id eq id }) {
            it[MBReleaseGroupTable.lastUpdate] = lastUpdate
        }
    }

    suspend fun updateReleaseLastUpdate(id: UUID, lastUpdate: Long) = dbQuery {
        MBReleaseTable.update({ MBReleaseTable.id eq id }) {
            it[MBReleaseTable.lastUpdate] = lastUpdate
        }
    }

    suspend fun updateRecordingLastUpdate(id: UUID, lastUpdate: Long) = dbQuery {
        MBRecordingTable.update({ MBRecordingTable.id eq id }) {
            it[MBRecordingTable.lastUpdate] = lastUpdate
        }
    }
}
