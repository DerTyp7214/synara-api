package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.data.ReleaseType
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.MusicBrainzService
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject
import java.util.UUID

@Migration("1.2")
class LinkRecentReleasesToLibrary : CustomMigration() {
    private val musicBrainzService by inject<MusicBrainzService>()

    private data class ReleaseInfo(val releaseGroupId: UUID, val type: ReleaseType)

    override suspend fun migrate() {
        val releases = dbQuery {
            RecentReleaseTable.join(
                ArtistMusicBrainzTable,
                JoinType.INNER,
                RecentReleaseTable.artistId,
                ArtistMusicBrainzTable.artistId
            ).selectAll().map {
                Triple(
                    it[ArtistMusicBrainzTable.artistId].value,
                    it[ArtistMusicBrainzTable.musicBrainzId],
                    ReleaseInfo(it[RecentReleaseTable.releaseId].value, it[RecentReleaseTable.type])
                )
            }
        }

        val groupedByArtist = releases.groupBy { it.first to it.second }

        groupedByArtist.forEach { (artist, artistReleases) ->
            val (artistId, mbArtistId) = artist
            if (mbArtistId == null) return@forEach

            logger.info("Processing artist $artistId (${mbArtistId.value}) for linking releases")

            val mbReleases = try {
                musicBrainzService.fetchReleasesByArtist(mbArtistId.value)
            } catch (e: Exception) {
                logger.error("Failed to fetch releases for artist ${mbArtistId.value}", e)
                emptyList()
            }

            val albumMappings = dbQuery {
                AlbumMusicBrainzTable.join(
                    AlbumArtistTable,
                    JoinType.INNER,
                    AlbumMusicBrainzTable.albumId,
                    AlbumArtistTable.albumId
                ).selectAll()
                    .where { AlbumArtistTable.artistId eq artistId }
                    .mapNotNull { it[AlbumMusicBrainzTable.musicBrainzId]?.let { mbId -> mbId.value to it[AlbumMusicBrainzTable.albumId].value } }
                    .toMap()
            }

            val songMappings = dbQuery {
                SongMusicBrainzTable.join(
                    SongArtistTable,
                    JoinType.INNER,
                    SongMusicBrainzTable.songId,
                    SongArtistTable.songId
                ).selectAll()
                    .where { SongArtistTable.artistId eq artistId }
                    .mapNotNull { it[SongMusicBrainzTable.musicBrainzId]?.let { mbId -> mbId.value to it[SongMusicBrainzTable.songId].value } }
                    .toMap()
            }

            artistReleases.forEach { (_, _, info) ->
                val (releaseGroupId, type) = info
                val groupReleases = mbReleases.filter { it.releaseGroup?.id == releaseGroupId }
                val groupReleaseIds = (groupReleases.map { it.id } + releaseGroupId).toSet()

                val libraryAlbumId = albumMappings.entries.find { (mbId, _) ->
                    groupReleaseIds.contains(mbId)
                }?.value

                val groupRecordingIds = if (type == ReleaseType.Single) {
                    try {
                        musicBrainzService.fetchRecordingsByReleaseGroup(releaseGroupId).map { it.id }.toSet()
                    } catch (e: Exception) {
                        logger.error("Failed to fetch recordings for release group $releaseGroupId", e)
                        emptySet()
                    }
                } else emptySet()

                val librarySongId = songMappings.entries.find { (mbId, _) ->
                    groupReleaseIds.contains(mbId) || groupRecordingIds.contains(mbId)
                }?.value

                if ((libraryAlbumId != null) || (librarySongId != null)) {
                    dbQuery {
                        RecentReleaseTable.update({ RecentReleaseTable.releaseId eq releaseGroupId }) {
                            it[RecentReleaseTable.albumId] = libraryAlbumId
                            it[RecentReleaseTable.songId] = librarySongId
                        }
                    }
                }
            }
        }
    }
}
