package dev.dertyp.services

import dev.dertyp.db.*
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

class LibraryMergeService : Service() {

    suspend fun mergeDuplicates(): Map<String, Any?> = dbQuery {
        val songsMerged = mergeDuplicateSongs()
        val imagesMerged = mergeDuplicateImages()
        mapOf(
            "songsMerged" to songsMerged,
            "imagesMerged" to imagesMerged,
            "totalMerged" to (songsMerged + imagesMerged)
        )
    }

    private fun mergeDuplicateSongs(): Int {
        logger.info("Starting duplicate song merge check")

        val duplicates = SongTable
            .select(
                SongTable.title,
                SongTable.albumId,
                SongTable.duration,
                SongTable.filePath,
                SongTable.trackNumber,
                SongTable.discNumber,
                SongTable.fileSize
            )
            .groupBy(
                SongTable.title,
                SongTable.albumId,
                SongTable.duration,
                SongTable.filePath,
                SongTable.trackNumber,
                SongTable.discNumber,
                SongTable.fileSize
            )
            .having { SongTable.id.count() greater 1L }
            .toList()

        if (duplicates.isEmpty()) {
            logger.info("No duplicate songs found")
            return 0
        }

        logger.info("Found ${duplicates.size} groups of duplicate songs")

        var totalMerged = 0
        for (duplicateGroup in duplicates) {
            val songsInGroup = SongTable
                .select(SongTable.id, SongTable.inserted)
                .where {
                    (SongTable.title eq duplicateGroup[SongTable.title]) and
                            (SongTable.albumId eq duplicateGroup[SongTable.albumId]) and
                            (SongTable.duration eq duplicateGroup[SongTable.duration]) and
                            (SongTable.filePath eq duplicateGroup[SongTable.filePath]) and
                            (SongTable.trackNumber eq duplicateGroup[SongTable.trackNumber]) and
                            (SongTable.discNumber eq duplicateGroup[SongTable.discNumber]) and
                            (SongTable.fileSize eq duplicateGroup[SongTable.fileSize])
                }
                .orderBy(SongTable.inserted, SortOrder.ASC) // Oldest first
                .map { it[SongTable.id].value }

            if (songsInGroup.size <= 1) continue

            val keptSongId = songsInGroup.first()
            val songsToMerge = songsInGroup.drop(1)

            logger.info("Merging ${songsToMerge.size} songs into $keptSongId")

            for (oldSongId in songsToMerge) {
                mergeSongReferences(oldSongId, keptSongId)
                SongTable.deleteWhere { SongTable.id eq oldSongId }
                totalMerged++
            }
        }
        logger.info("Duplicate song merge completed")
        return totalMerged
    }

    private fun mergeDuplicateImages(): Int {
        logger.info("Starting duplicate image merge check")

        val duplicates = ImageTable
            .select(ImageTable.imageHash)
            .groupBy(ImageTable.imageHash)
            .having { ImageTable.id.count() greater 1L }
            .toList()

        if (duplicates.isEmpty()) {
            logger.info("No duplicate images found")
            return 0
        }

        logger.info("Found ${duplicates.size} groups of duplicate images")

        var totalMerged = 0
        for (duplicateGroup in duplicates) {
            val hash = duplicateGroup[ImageTable.imageHash]
            val imagesInGroup = ImageTable
                .select(ImageTable.id)
                .where { ImageTable.imageHash eq hash }
                .map { it[ImageTable.id].value }

            if (imagesInGroup.size <= 1) continue

            val keptImageId = imagesInGroup.first()
            val imagesToMerge = imagesInGroup.drop(1)

            logger.info("Merging ${imagesToMerge.size} images with hash $hash into $keptImageId")

            for (oldImageId in imagesToMerge) {
                mergeImageReferences(oldImageId, keptImageId)
                ImageTable.deleteWhere { ImageTable.id eq oldImageId }
                totalMerged++
            }
        }
        logger.info("Duplicate image merge completed")
        return totalMerged
    }

    private fun mergeSongReferences(oldSongId: UUID, keptSongId: UUID) {
        val artistsForOld = SongArtistTable.select(SongArtistTable.artistId).where { SongArtistTable.songId eq oldSongId }.map { it[SongArtistTable.artistId].value }
        val artistsForKept = SongArtistTable.select(SongArtistTable.artistId).where { SongArtistTable.songId eq keptSongId }.map { it[SongArtistTable.artistId].value }.toSet()

        for (artistId in artistsForOld) {
            if (artistId !in artistsForKept) {
                SongArtistTable.update({ (SongArtistTable.songId eq oldSongId) and (SongArtistTable.artistId eq artistId) }) {
                    it[SongArtistTable.songId] = keptSongId
                }
            }
        }
        SongArtistTable.deleteWhere { SongArtistTable.songId eq oldSongId }

        val playlistsForOld = PlaylistSongTable.select(PlaylistSongTable.playlistId).where { PlaylistSongTable.songId eq oldSongId }.map { it[PlaylistSongTable.playlistId].value }
        val playlistsForKept = PlaylistSongTable.select(PlaylistSongTable.playlistId).where { PlaylistSongTable.songId eq keptSongId }.map { it[PlaylistSongTable.playlistId].value }.toSet()

        for (playlistId in playlistsForOld) {
            if (playlistId !in playlistsForKept) {
                PlaylistSongTable.update({ (PlaylistSongTable.songId eq oldSongId) and (PlaylistSongTable.playlistId eq playlistId) }) {
                    it[PlaylistSongTable.songId] = keptSongId
                }
            }
        }
        PlaylistSongTable.deleteWhere { PlaylistSongTable.songId eq oldSongId }

        UserPlaylistSongTable.update({ UserPlaylistSongTable.songId eq oldSongId }) {
            it[UserPlaylistSongTable.songId] = keptSongId
        }

        val usersForOld = UserSongTable.select(UserSongTable.userId, UserSongTable.isFavourite).where { UserSongTable.songId eq oldSongId }.toList()
        val usersForKept = UserSongTable.select(UserSongTable.userId, UserSongTable.isFavourite).where { UserSongTable.songId eq keptSongId }.associate { it[UserSongTable.userId].value to it[UserSongTable.isFavourite] }

        for (row in usersForOld) {
            val userId = row[UserSongTable.userId].value
            val isFav = row[UserSongTable.isFavourite]

            if (userId !in usersForKept) {
                UserSongTable.update({ (UserSongTable.songId eq oldSongId) and (UserSongTable.userId eq userId) }) {
                    it[UserSongTable.songId] = keptSongId
                }
            } else {
                if (isFav && !usersForKept[userId]!!) {
                    UserSongTable.update({ (UserSongTable.songId eq keptSongId) and (UserSongTable.userId eq userId) }) {
                        it[UserSongTable.isFavourite] = true
                    }
                }
            }
        }
        UserSongTable.deleteWhere { UserSongTable.songId eq oldSongId }

        val transForOld = TranscodedSongTable.select(TranscodedSongTable.bitrate).where { TranscodedSongTable.songId eq oldSongId }.map { it[TranscodedSongTable.bitrate] }
        val transForKept = TranscodedSongTable.select(TranscodedSongTable.bitrate).where { TranscodedSongTable.songId eq keptSongId }.map { it[TranscodedSongTable.bitrate] }.toSet()

        for (bitrate in transForOld) {
            if (bitrate !in transForKept) {
                TranscodedSongTable.update({ (TranscodedSongTable.songId eq oldSongId) and (TranscodedSongTable.bitrate eq bitrate) }) {
                    it[TranscodedSongTable.songId] = keptSongId
                }
            }
        }
        TranscodedSongTable.deleteWhere { TranscodedSongTable.songId eq oldSongId }

        val hasMbKept = SongMusicBrainzTable.select(SongMusicBrainzTable.songId).where { SongMusicBrainzTable.songId eq keptSongId }.any()
        if (!hasMbKept) {
            SongMusicBrainzTable.update({ SongMusicBrainzTable.songId eq oldSongId }) {
                it[SongMusicBrainzTable.songId] = keptSongId
            }
        }
        SongMusicBrainzTable.deleteWhere { SongMusicBrainzTable.songId eq oldSongId }
    }

    private fun mergeImageReferences(oldImageId: UUID, keptImageId: UUID) {
        SongTable.update({ SongTable.cover eq oldImageId }) {
            it[SongTable.cover] = keptImageId
        }
        AlbumTable.update({ AlbumTable.cover eq oldImageId }) {
            it[AlbumTable.cover] = keptImageId
        }
        ArtistTable.update({ ArtistTable.image eq oldImageId }) {
            it[ArtistTable.image] = keptImageId
        }
        UserTable.update({ UserTable.profileImage eq oldImageId }) {
            it[UserTable.profileImage] = keptImageId
        }
        PlaylistTable.update({ PlaylistTable.imageId eq oldImageId }) {
            it[PlaylistTable.imageId] = keptImageId
        }
        UserPlaylistTable.update({ UserPlaylistTable.imageId eq oldImageId }) {
            it[UserPlaylistTable.imageId] = keptImageId
        }
    }
}
