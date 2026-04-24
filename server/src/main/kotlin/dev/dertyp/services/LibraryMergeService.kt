package dev.dertyp.services

import dev.dertyp.db.AlbumArtistTable
import dev.dertyp.db.AlbumMusicBrainzTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.PlaylistSongTable
import dev.dertyp.db.PlaylistTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongMusicBrainzTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.TranscodedSongTable
import dev.dertyp.db.UserPlaylistSongTable
import dev.dertyp.db.UserPlaylistTable
import dev.dertyp.db.UserSongTable
import dev.dertyp.db.UserTable
import dev.dertyp.dbQuery
import dev.dertyp.plugins.PluginManager
import io.ktor.server.application.ApplicationEnvironment
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.get
import org.koin.core.component.inject
import java.util.UUID

class LibraryMergeService(
    private val environment: ApplicationEnvironment
) : Service() {
    private val pluginManager by inject<PluginManager>()

    suspend fun mergeDuplicates(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Map<String, Any?> = dbQuery {
        onProgress(0.0, "Merging duplicate songs...")
        val songsMerged = mergeDuplicateSongs()
        
        onProgress(25.0, "Merging same album songs...")
        val sameAlbumSongsMerged = mergeSameAlbumSongs()
        
        onProgress(50.0, "Merging duplicate images...")
        val imagesMerged = mergeDuplicateImages()
        
        onProgress(75.0, "Merging duplicate albums...")
        val albumsMerged = mergeDuplicateAlbums()
        
        onProgress(100.0, "Library merge completed")
        mapOf(
            "songsMerged" to songsMerged,
            "sameAlbumSongsMerged" to sameAlbumSongsMerged,
            "imagesMerged" to imagesMerged,
            "albumsMerged" to albumsMerged,
            "totalMerged" to (songsMerged + sameAlbumSongsMerged + imagesMerged + albumsMerged),
        )
    }

    fun mergeDuplicateSongs(): Int {
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
                .orderBy(SongTable.inserted, SortOrder.ASC)
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

    suspend fun mergeSameAlbumSongs(): Int {
        logger.info("Starting same-album duplicate song merge check")

        val duplicates = SongTable
            .select(
                SongTable.albumId,
                SongTable.title,
                SongTable.trackNumber,
                SongTable.discNumber
            )
            .groupBy(
                SongTable.albumId,
                SongTable.title,
                SongTable.trackNumber,
                SongTable.discNumber
            )
            .having { SongTable.id.count() greater 1L }
            .toList()

        if (duplicates.isEmpty()) {
            logger.info("No same-album duplicate songs found")
            return 0
        }

        logger.info("Found ${duplicates.size} groups of same-album duplicate songs")

        val allSongsToDelete = mutableListOf<UUID>()
        var totalMerged = 0

        for (duplicateGroup in duplicates) {
            val songsInGroup = SongTable
                .select(SongTable.id, SongTable.fileSize, SongTable.inserted)
                .where {
                    (SongTable.albumId eq duplicateGroup[SongTable.albumId]) and
                            (SongTable.title eq duplicateGroup[SongTable.title]) and
                            (SongTable.trackNumber eq duplicateGroup[SongTable.trackNumber]) and
                            (SongTable.discNumber eq duplicateGroup[SongTable.discNumber])
                }
                .orderBy(SongTable.fileSize, SortOrder.DESC)
                .orderBy(SongTable.inserted, SortOrder.ASC)
                .map { it[SongTable.id].value }

            if (songsInGroup.size <= 1) continue

            val keptSongId = songsInGroup.first()
            val songsToMerge = songsInGroup.drop(1)

            logger.info("Merging ${songsToMerge.size} songs into $keptSongId")

            for (oldSongId in songsToMerge) {
                mergeSongReferences(oldSongId, keptSongId)
                allSongsToDelete.add(oldSongId)
                totalMerged++
            }
        }

        if (allSongsToDelete.isNotEmpty()) {
            val songService = get<SongService>()
            allSongsToDelete.chunked(10000).forEach {
                songService.deleteSongs(it)
            }
        }

        logger.info("Same-album duplicate song merge completed")
        return totalMerged
    }

    suspend fun mergeDuplicateAlbums(): Int {
        logger.info("Starting duplicate album merge check")
        var totalMerged = 0

        val allAlbums = AlbumTable.leftJoin(AlbumMusicBrainzTable).selectAll().toList()

        val mbIdGroups = allAlbums
            .filter { it.getOrNull(AlbumMusicBrainzTable.musicBrainzId) != null }
            .groupBy { it[AlbumMusicBrainzTable.musicBrainzId]!!.value }
            .filter { it.value.size > 1 }

        for ((mbId, group) in mbIdGroups) {
            val sortedGroup = group.sortedByDescending {
                var score = 0
                if (it[AlbumTable.releaseDate] != null) score++
                if (it[AlbumTable.cover] != null) score++
                (score * 1000) + it[AlbumTable.songCount]
            }

            val keptAlbum = sortedGroup.first()
            val keptAlbumId = keptAlbum[AlbumTable.id].value
            val albumsToMerge = sortedGroup.drop(1)

            logger.info("Merging ${albumsToMerge.size} albums with musicBrainzId $mbId into $keptAlbumId")

            for (oldAlbum in albumsToMerge) {
                mergeAlbumReferences(oldAlbum[AlbumTable.id].value, keptAlbumId)
                AlbumTable.deleteWhere { AlbumTable.id eq oldAlbum[AlbumTable.id].value }
                totalMerged++
            }

            if (totalMerged > 0) {
                val albumService = get<AlbumService>()
                albumService.fetchMusicBrainzId(keptAlbumId, triggerMerge = false)
            }
        }

        val remainingAlbums = if (totalMerged > 0) AlbumTable.leftJoin(AlbumMusicBrainzTable).selectAll().toList() else allAlbums

        val originalIdGroups = remainingAlbums
            .filter { it[AlbumTable.originalId] != null }
            .groupBy { 
                val id = it[AlbumTable.originalId]!!
                if (id.contains(":")) id else "tidal:$id"
            }
            .filter { it.value.size > 1 }

        for ((originalId, group) in originalIdGroups) {
            val sortedGroup = group.sortedByDescending {
                var score = 0
                if (it[AlbumTable.releaseDate] != null) score++
                if (it[AlbumTable.cover] != null) score++
                if (it.getOrNull(AlbumMusicBrainzTable.musicBrainzId) != null) score++
                (score * 1000) + it[AlbumTable.songCount]
            }

            val keptAlbum = sortedGroup.first()
            val keptAlbumId = keptAlbum[AlbumTable.id].value
            val albumsToMerge = sortedGroup.drop(1)

            logger.info("Merging ${albumsToMerge.size} albums with originalId $originalId into $keptAlbumId")

            for (oldAlbum in albumsToMerge) {
                mergeAlbumReferences(oldAlbum[AlbumTable.id].value, keptAlbumId)
                AlbumTable.deleteWhere { AlbumTable.id eq oldAlbum[AlbumTable.id].value }
                totalMerged++
            }

            val musicBrainzId = keptAlbum.getOrNull(AlbumMusicBrainzTable.musicBrainzId)?.value
            if (musicBrainzId != null) {
                val albumService = get<AlbumService>()
                albumService.fetchMusicBrainzId(keptAlbumId, triggerMerge = false)
            } else {
                val prefix = originalId.substringBefore(":")
                val downloader = pluginManager.getAllDownloaders().find { it.id == prefix }
                downloader?.updateAlbumMetadata(keptAlbumId, originalId)
            }
        }

        val albumsWithArtists = allAlbums.map { albumRow ->
            val albumId = albumRow[AlbumTable.id].value
            val artists = AlbumArtistTable
                .select(AlbumArtistTable.artistId)
                .where { AlbumArtistTable.albumId eq albumId }
                .map { it[AlbumArtistTable.artistId].value }
            albumRow to artists
        }

        val processedAlbumIds = mutableSetOf<UUID>()
        val groups = mutableListOf<MutableList<ResultRow>>()

        for (i in albumsWithArtists.indices) {
            val (albumA, artistsA) = albumsWithArtists[i]
            val albumAId = albumA[AlbumTable.id].value
            if (albumAId in processedAlbumIds) continue

            val currentGroup = mutableListOf(albumA)
            processedAlbumIds.add(albumAId)

            for (j in i + 1 until albumsWithArtists.size) {
                val (albumB, artistsB) = albumsWithArtists[j]
                val albumBId = albumB[AlbumTable.id].value
                if (albumBId in processedAlbumIds) continue

                if (calculateSimilarity(albumA, albumB, artistsA, artistsB) > 140) {
                    currentGroup.add(albumB)
                    processedAlbumIds.add(albumBId)
                }
            }
            if (currentGroup.size > 1) {
                groups.add(currentGroup)
            }
        }

        for (group in groups) {
            logger.info("Found a group of ${group.size} similar albums to merge.")

            val sortedGroup = group.sortedByDescending {
                var score = 0
                if (it[AlbumTable.releaseDate] != null) score++
                if (it[AlbumTable.cover] != null) score++
                if (it.getOrNull(AlbumMusicBrainzTable.musicBrainzId) != null) score++
                (score * 1000) + it[AlbumTable.songCount]
            }

            val keptAlbum = sortedGroup.first()
            val keptAlbumId = keptAlbum[AlbumTable.id].value
            val albumsToMerge = sortedGroup.drop(1)
            
            logger.info("Merging ${albumsToMerge.size} albums into $keptAlbumId")

            for (oldAlbum in albumsToMerge) {
                mergeAlbumReferences(oldAlbum[AlbumTable.id].value, keptAlbumId)
                AlbumTable.deleteWhere { AlbumTable.id eq oldAlbum[AlbumTable.id].value }
                totalMerged++
            }
        }

        logger.info("Duplicate album merge completed")
        return totalMerged
    }

    private fun calculateSimilarity(albumA: ResultRow, albumB: ResultRow, artistsA: List<UUID>, artistsB: List<UUID>): Int {
        var score = 0

        val coverA = albumA.getOrNull(AlbumTable.cover)?.value
        val coverB = albumB.getOrNull(AlbumTable.cover)?.value
        if (coverA != null && coverA == coverB) {
            score += 100
        }

        val nameA = albumA[AlbumTable.name]
        val nameB = albumB[AlbumTable.name]
        val dateA = albumA.getOrNull(AlbumTable.releaseDate)
        val dateB = albumB.getOrNull(AlbumTable.releaseDate)
        if (nameA.equals(nameB, ignoreCase = true) && dateA != null && dateA == dateB) {
            score += 70
        }

        if (nameA.equals(nameB, ignoreCase = true)) {
            score += 20
        }
        if (dateA != null && dateA == dateB) {
            score += 10
        }

        val intersection = artistsA.intersect(artistsB.toSet()).size
        val union = artistsA.size + artistsB.size - intersection
        if (union > 0) {
            val jaccard = intersection.toDouble() / union
            score += (jaccard * 50).toInt()
        }

        return score
    }

    private fun mergeAlbumReferences(oldAlbumId: UUID, keptAlbumId: UUID) {
        val songsForOld = SongTable.select(SongTable.id).where { SongTable.albumId eq oldAlbumId }.map { it[SongTable.id].value }
        if (songsForOld.isNotEmpty()) {
            SongTable.update({ SongTable.albumId eq oldAlbumId }) {
                it[SongTable.albumId] = keptAlbumId
            }
        }

        val artistsForOld = AlbumArtistTable.select(AlbumArtistTable.artistId).where { AlbumArtistTable.albumId eq oldAlbumId }.map { it[AlbumArtistTable.artistId].value }
        val artistsForKept = AlbumArtistTable.select(AlbumArtistTable.artistId).where { AlbumArtistTable.albumId eq keptAlbumId }.map { it[AlbumArtistTable.artistId].value }.toSet()

        for (artistId in artistsForOld) {
            if (artistId !in artistsForKept) {
                AlbumArtistTable.update({ (AlbumArtistTable.albumId eq oldAlbumId) and (AlbumArtistTable.artistId eq artistId) }) {
                    it[AlbumArtistTable.albumId] = keptAlbumId
                }
            }
        }
        AlbumArtistTable.deleteWhere { AlbumArtistTable.albumId eq oldAlbumId }

        val hasMbKept = AlbumMusicBrainzTable.select(AlbumMusicBrainzTable.albumId).where { AlbumMusicBrainzTable.albumId eq keptAlbumId }.any()
        if (!hasMbKept) {
            AlbumMusicBrainzTable.update({ AlbumMusicBrainzTable.albumId eq oldAlbumId }) {
                it[AlbumMusicBrainzTable.albumId] = keptAlbumId
            }
        }
        AlbumMusicBrainzTable.deleteWhere { AlbumMusicBrainzTable.albumId eq oldAlbumId }
    }

    private fun mergeDuplicateImages(): Int {
        logger.info("Starting duplicate image merge check")

        var totalMerged = 0
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
