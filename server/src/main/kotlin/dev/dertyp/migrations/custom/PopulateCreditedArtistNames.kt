package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.ArtistService
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject
import java.util.UUID

@Migration("3.3")
class PopulateCreditedArtistNames : CustomMigration() {
    private val artistService by inject<ArtistService>()

    override suspend fun migrate() {
        logTask("Populate credited artist names") {
            val songMatches: List<Triple<UUID, UUID, String>> = dbQuery {
                SongArtistTable
                    .innerJoin(SongMusicBrainzTable, onColumn = { SongArtistTable.songId }, otherColumn = { SongMusicBrainzTable.songId })
                    .innerJoin(ArtistMusicBrainzTable, onColumn = { SongArtistTable.artistId }, otherColumn = { ArtistMusicBrainzTable.artistId })
                    .innerJoin(ArtistTable, onColumn = { SongArtistTable.artistId }, otherColumn = { ArtistTable.id })
                    .innerJoin(MBRecordingArtistCreditTable, onColumn = { SongMusicBrainzTable.musicBrainzId }, otherColumn = { MBRecordingArtistCreditTable.recordingId })
                    .select(SongArtistTable.songId, SongArtistTable.artistId, MBRecordingArtistCreditTable.name, ArtistTable.name)
                    .where {
                        SongArtistTable.creditedAliasId.isNull() and
                            (MBRecordingArtistCreditTable.artistId eq ArtistMusicBrainzTable.musicBrainzId)
                    }
                    .orderBy(MBRecordingArtistCreditTable.position)
                    .map { row ->
                        (row[SongArtistTable.songId].value to row[SongArtistTable.artistId].value) to
                            (row[MBRecordingArtistCreditTable.name] to row[ArtistTable.name])
                    }
                    .distinctBy { it.first }
                    .mapNotNull { (key, names) ->
                        val (credited, canonical) = names
                        if (credited.isBlank() || credited == canonical) null
                        else Triple(key.first, key.second, credited)
                    }
            }

            dbQuery {
                songMatches.forEachIndexed { index, (songId, artistId, credited) ->
                    val aliasId = artistService.getOrCreateAliasTx(artistId, credited)
                    SongArtistTable.update({ (SongArtistTable.songId eq songId) and (SongArtistTable.artistId eq artistId) }) {
                        it[creditedAliasId] = aliasId
                    }
                    if (index % 100 == 0 || index == songMatches.lastIndex) {
                        val progress = if (songMatches.isNotEmpty()) (index.toDouble() / songMatches.size / 2.0) else 0.5
                        updateProgress(progress, "Crediting song artists: ${index + 1}/${songMatches.size}")
                    }
                }
            }

            val albumMatches: List<Triple<UUID, UUID, String>> = dbQuery {
                AlbumArtistTable
                    .innerJoin(AlbumMusicBrainzTable, onColumn = { AlbumArtistTable.albumId }, otherColumn = { AlbumMusicBrainzTable.albumId })
                    .innerJoin(ArtistMusicBrainzTable, onColumn = { AlbumArtistTable.artistId }, otherColumn = { ArtistMusicBrainzTable.artistId })
                    .innerJoin(ArtistTable, onColumn = { AlbumArtistTable.artistId }, otherColumn = { ArtistTable.id })
                    .innerJoin(MBReleaseArtistCreditTable, onColumn = { AlbumMusicBrainzTable.musicBrainzId }, otherColumn = { MBReleaseArtistCreditTable.releaseId })
                    .select(AlbumArtistTable.albumId, AlbumArtistTable.artistId, MBReleaseArtistCreditTable.name, ArtistTable.name)
                    .where {
                        AlbumArtistTable.creditedAliasId.isNull() and
                            (MBReleaseArtistCreditTable.artistId eq ArtistMusicBrainzTable.musicBrainzId)
                    }
                    .orderBy(MBReleaseArtistCreditTable.position)
                    .map { row ->
                        (row[AlbumArtistTable.albumId].value to row[AlbumArtistTable.artistId].value) to
                            (row[MBReleaseArtistCreditTable.name] to row[ArtistTable.name])
                    }
                    .distinctBy { it.first }
                    .mapNotNull { (key, names) ->
                        val (credited, canonical) = names
                        if (credited.isBlank() || credited == canonical) null
                        else Triple(key.first, key.second, credited)
                    }
            }

            dbQuery {
                albumMatches.forEachIndexed { index, (albumId, artistId, credited) ->
                    val aliasId = artistService.getOrCreateAliasTx(artistId, credited)
                    AlbumArtistTable.update({ (AlbumArtistTable.albumId eq albumId) and (AlbumArtistTable.artistId eq artistId) }) {
                        it[creditedAliasId] = aliasId
                    }
                    if (index % 10 == 0 || index == albumMatches.lastIndex) {
                        val progress = if (albumMatches.isNotEmpty()) 0.5 + (index.toDouble() / albumMatches.size / 2.0) else 1.0
                        updateProgress(progress, "Crediting album artists: ${index + 1}/${albumMatches.size}")
                    }
                }
            }

            mapOf("songCredits" to songMatches.size, "albumCredits" to albumMatches.size)
        }
    }
}
