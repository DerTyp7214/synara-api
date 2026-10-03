package dev.dertyp.core

import dev.dertyp.db.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

private const val CREDIT_ORDER_CHUNK_SIZE = 5000

private class CreditLinkColumns(
    val table: Table,
    val owner: Column<EntityID<UUID>>,
    val artist: Column<EntityID<UUID>>,
    val position: Column<Int>,
    val joinPhrase: Column<String?>,
)

private val songLinkColumns = CreditLinkColumns(
    SongArtistTable,
    SongArtistTable.songId,
    SongArtistTable.artistId,
    SongArtistTable.position,
    SongArtistTable.joinPhrase,
)

private val albumLinkColumns = CreditLinkColumns(
    AlbumArtistTable,
    AlbumArtistTable.albumId,
    AlbumArtistTable.artistId,
    AlbumArtistTable.position,
    AlbumArtistTable.joinPhrase,
)

fun applyCachedSongCreditOrder(songIds: Collection<UUID>): Int =
    applyCachedCreditOrder(songLinkColumns, songIds) { chunk ->
        SongArtistTable
            .innerJoin(
                SongMusicBrainzTable,
                onColumn = { SongArtistTable.songId },
                otherColumn = { SongMusicBrainzTable.songId })
            .innerJoin(
                ArtistMusicBrainzTable,
                onColumn = { SongArtistTable.artistId },
                otherColumn = { ArtistMusicBrainzTable.artistId })
            .innerJoin(
                MBRecordingArtistCreditTable,
                onColumn = { SongMusicBrainzTable.musicBrainzId },
                otherColumn = { MBRecordingArtistCreditTable.recordingId })
            .select(
                SongArtistTable.songId,
                SongArtistTable.artistId,
                MBRecordingArtistCreditTable.position,
                MBRecordingArtistCreditTable.joinPhrase
            )
            .where { SongArtistTable.songId inList chunk }
            .andWhere { MBRecordingArtistCreditTable.artistId eq ArtistMusicBrainzTable.musicBrainzId }
            .map {
                CreditLink(
                    it[SongArtistTable.songId].value,
                    it[SongArtistTable.artistId].value,
                    it[MBRecordingArtistCreditTable.position],
                    it[MBRecordingArtistCreditTable.joinPhrase],
                )
            }
    }

fun applyCachedAlbumCreditOrder(albumIds: Collection<UUID>): Int =
    applyCachedCreditOrder(albumLinkColumns, albumIds) { chunk ->
        AlbumArtistTable
            .innerJoin(
                AlbumMusicBrainzTable,
                onColumn = { AlbumArtistTable.albumId },
                otherColumn = { AlbumMusicBrainzTable.albumId })
            .innerJoin(
                ArtistMusicBrainzTable,
                onColumn = { AlbumArtistTable.artistId },
                otherColumn = { ArtistMusicBrainzTable.artistId })
            .innerJoin(
                MBReleaseArtistCreditTable,
                onColumn = { AlbumMusicBrainzTable.musicBrainzId },
                otherColumn = { MBReleaseArtistCreditTable.releaseId })
            .select(
                AlbumArtistTable.albumId,
                AlbumArtistTable.artistId,
                MBReleaseArtistCreditTable.position,
                MBReleaseArtistCreditTable.joinPhrase
            )
            .where { AlbumArtistTable.albumId inList chunk }
            .andWhere { MBReleaseArtistCreditTable.artistId eq ArtistMusicBrainzTable.musicBrainzId }
            .map {
                CreditLink(
                    it[AlbumArtistTable.albumId].value,
                    it[AlbumArtistTable.artistId].value,
                    it[MBReleaseArtistCreditTable.position],
                    it[MBReleaseArtistCreditTable.joinPhrase],
                )
            }
    }

private fun applyCachedCreditOrder(
    columns: CreditLinkColumns,
    ownerIds: Collection<UUID>,
    cachedCredits: (List<UUID>) -> List<CreditLink>,
): Int = ownerIds.distinct().chunked(CREDIT_ORDER_CHUNK_SIZE).sumOf { chunk ->
    val creditsByOwner = cachedCredits(chunk)
        .groupBy { it.ownerId to it.artistId }.values
        .map { credits -> credits.minBy { it.position } }
        .groupBy { it.ownerId }
    if (creditsByOwner.isEmpty()) return@sumOf 0

    val linksByOwner = columns.table
        .select(columns.owner, columns.artist, columns.position)
        .where { columns.owner inList creditsByOwner.keys }
        .map { CreditLink(it[columns.owner].value, it[columns.artist].value, it[columns.position], null) }
        .groupBy { it.ownerId }

    creditsByOwner.forEach { (ownerId, credits) ->
        val creditByArtist = credits.associateBy { it.artistId }
        val firstUnmatchedPosition = credits.maxOf { it.position } + 1
        val unmatched = linksByOwner[ownerId].orEmpty()
            .filter { it.artistId !in creditByArtist }
            .sortedWith(creditLinkOrder)
            .mapIndexed { index, link -> link.copy(position = firstUnmatchedPosition + index) }
        (credits + unmatched).forEach { link ->
            columns.table.update({ (columns.owner eq ownerId) and (columns.artist eq link.artistId) }) {
                it[columns.position] = link.position
                it[columns.joinPhrase] = link.joinPhrase
            }
        }
    }
    creditsByOwner.values.sumOf { it.size }
}
