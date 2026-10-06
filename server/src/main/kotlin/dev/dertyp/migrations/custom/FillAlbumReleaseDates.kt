package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.date.getDateFromISO
import dev.dertyp.core.date.getISOFromDate
import dev.dertyp.core.db.dbQuery
import dev.dertyp.core.logTask
import dev.dertyp.data.EntityType
import dev.dertyp.db.AlbumMusicBrainzTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.MBReleaseTable
import dev.dertyp.db.SongTable
import dev.dertyp.plugins.parsePartialDate
import dev.dertyp.services.EntityChangeRecorder
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val CHUNK_SIZE = 1000

@Migration("3.32")
class FillAlbumReleaseDates : CustomMigration() {
    private val entityChangeRecorder by inject<EntityChangeRecorder>()

    override suspend fun migrate() {
        logTask("Fill album release dates") {
            val undated = dbQuery {
                AlbumTable
                    .select(AlbumTable.id, AlbumTable.releaseDate)
                    .where { AlbumTable.releaseDateEstimated eq false }
                    .filter { getDateFromISO(it[AlbumTable.releaseDate]) == null }
                    .associate { it[AlbumTable.id].value to it[AlbumTable.releaseDate] }
            }

            val total = undated.size
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            var done = 0
            var estimated = 0

            for (chunk in undated.keys.chunked(CHUNK_SIZE)) {
                dbQuery {
                    val releaseDates = AlbumMusicBrainzTable
                        .innerJoin(
                            MBReleaseTable,
                            onColumn = { AlbumMusicBrainzTable.musicBrainzId },
                            otherColumn = { MBReleaseTable.id })
                        .select(AlbumMusicBrainzTable.albumId, MBReleaseTable.date)
                        .where { AlbumMusicBrainzTable.albumId inList chunk }
                        .mapNotNull { row ->
                            parsePartialDate(row[MBReleaseTable.date])?.let { row[AlbumMusicBrainzTable.albumId].value to it }
                        }
                        .toMap()

                    val songDates = SongTable
                        .select(SongTable.albumId, SongTable.releaseDate)
                        .where { SongTable.albumId inList chunk }
                        .andWhere { SongTable.releaseDate.isNotNull() }
                        .withDistinct()
                        .mapNotNull { row ->
                            parsePartialDate(row[SongTable.releaseDate])?.let { row[SongTable.albumId].value to it }
                        }
                        .groupBy({ it.first }, { it.second })
                        .mapValues { (_, dates) -> dates.min() }

                    val firstImport = SongTable.inserted.min()
                    val importDays = SongTable
                        .select(SongTable.albumId, firstImport)
                        .where { SongTable.albumId inList chunk }
                        .groupBy(SongTable.albumId)
                        .mapNotNull { row ->
                            row[firstImport]?.let {
                                row[SongTable.albumId].value to Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
                            }
                        }
                        .toMap()

                    val resolved = chunk.associateWith { albumId ->
                        val known = parsePartialDate(undated[albumId]) ?: releaseDates[albumId] ?: songDates[albumId]
                        if (known != null) known to false else (importDays[albumId] ?: today) to true
                    }

                    for ((resolution, albumIds) in resolved.entries.groupBy({ it.value }, { it.key })) {
                        AlbumTable.update({ AlbumTable.id inList albumIds }) {
                            it[releaseDate] = getISOFromDate(resolution.first)
                            it[releaseDateEstimated] = resolution.second
                        }
                    }
                    entityChangeRecorder.updated(
                        EntityType.ALBUM,
                        resolved.filter { (albumId, resolution) -> getDateFromISO(undated[albumId]) != resolution.first }.keys
                    )
                    estimated += resolved.values.count { it.second }
                }
                done += chunk.size
                updateProgress(done.toDouble() / total, "Albums $done/$total, estimated $estimated")
            }

            logger.info("Filled the release date of $total album(s), $estimated from the import day")
            mapOf("albums" to total, "estimated" to estimated)
        }
    }
}
