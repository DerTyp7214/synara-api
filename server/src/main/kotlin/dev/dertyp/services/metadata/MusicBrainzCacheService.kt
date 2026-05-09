package dev.dertyp.services.metadata

import dev.dertyp.core.fetchBatchedResultsByIdKeyset
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.Service
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class MusicBrainzCacheService : Service() {

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

            MusicBrainzRecording(
                id = id,
                title = row[MBRecordingTable.title],
                length = row[MBRecordingTable.length],
                artistCredit = artistCredits,
                releases = releases,
                fetchedAt = row[MBRecordingTable.lastUpdate]
            )
        }
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
                artistCredit = artistCredits,
                media = media,
                fetchedAt = row[MBReleaseTable.lastUpdate]
            )
        }
    }

    suspend fun getReleaseGroup(id: UUID): MusicBrainzReleaseGroup? = dbQuery {
        MBReleaseGroupTable.selectAll().where { MBReleaseGroupTable.id eq id }.singleOrNull()?.let { row ->
            MusicBrainzReleaseGroup(
                id = id,
                title = row[MBReleaseGroupTable.title],
                primaryType = row[MBReleaseGroupTable.primaryType],
                firstReleaseDate = row[MBReleaseGroupTable.firstReleaseDate],
                fetchedAt = row[MBReleaseGroupTable.lastUpdate]
            )
        }
    }

    suspend fun updateArtistCache(artist: MusicBrainzArtist) = dbQuery {
        MBArtistTable.upsert(MBArtistTable.id) {
            it[id] = artist.id
            it[name] = artist.name ?: ""
            it[sortName] = artist.sortName ?: ""
            it[type] = artist.type?.name
            it[disambiguation] = artist.disambiguation
            it[country] = artist.country
            it[lifeSpanBegin] = artist.lifeSpan?.begin
            it[lifeSpanEnd] = artist.lifeSpan?.end
            it[lifeSpanEnded] = artist.lifeSpan?.ended
            it[lastUpdate] = Clock.System.now().toEpochMilliseconds()
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
                MBArtistTagTable.insert {
                    it[artistId] = artist.id
                    it[name] = tag.name
                    it[count] = tag.count
                }
            }
        }
    }

    suspend fun updateAreaCache(area: MusicBrainzArea) = dbQuery {
        MBAreaTable.upsert(MBAreaTable.id) {
            it[id] = area.id
            it[name] = area.name ?: ""
            it[sortName] = area.sortName ?: ""
        }
    }

    suspend fun updateRecordingCache(recording: MusicBrainzRecording) = dbQuery {
        MBRecordingTable.upsert(MBRecordingTable.id) {
            it[id] = recording.id
            it[title] = recording.title ?: ""
            it[length] = recording.length
            it[lastUpdate] = Clock.System.now().toEpochMilliseconds()
        }

        MBRecordingArtistCreditTable.deleteWhere { MBRecordingArtistCreditTable.recordingId eq recording.id }
        recording.artistCredit?.forEachIndexed { index, credit ->
            credit.artist?.let { mbArtist ->
                updateArtistCache(mbArtist)

                MBRecordingArtistCreditTable.insert {
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
    }

    suspend fun updateReleaseCache(release: MusicBrainzRelease): Unit = dbQuery {
        MBReleaseTable.upsert(MBReleaseTable.id) {
            it[id] = release.id
            it[title] = release.title ?: ""
            it[status] = release.status
            it[quality] = release.quality
            it[barcode] = release.barcode
            it[country] = release.country
            it[date] = release.date
            it[disambiguation] = release.disambiguation
            it[lastUpdate] = Clock.System.now().toEpochMilliseconds()
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

                MBReleaseArtistCreditTable.insert {
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

                MBTrackTable.upsert(MBTrackTable.id) {
                    it[id] = track.id
                    it[MBTrackTable.mediaId] = mediaId
                    it[position] = track.position
                    it[number] = track.number
                    it[title] = track.title
                    it[recordingId] = track.recording?.id
                }
            }
        }
    }

    suspend fun updateReleaseGroupCache(group: MusicBrainzReleaseGroup) = dbQuery {
        MBReleaseGroupTable.upsert(MBReleaseGroupTable.id) {
            it[id] = group.id
            it[title] = group.title
            it[primaryType] = group.primaryType
            it[firstReleaseDate] = group.firstReleaseDate
            it[lastUpdate] = Clock.System.now().toEpochMilliseconds()
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
