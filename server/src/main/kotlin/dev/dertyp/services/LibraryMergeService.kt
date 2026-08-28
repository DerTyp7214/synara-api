package dev.dertyp.services

import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.plugins.PluginManager
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.get
import org.koin.core.component.inject
import java.util.UUID

class LibraryMergeService : Service() {
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

        val perfectDuplicates = SongTable
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

        var totalMerged = 0
        for (duplicateGroup in perfectDuplicates) {
            val songsInGroup = SongTable
                .select(SongTable.id, SongTable.inserted, SongTable.title, SongTable.explicit, SongTable.atmosPath)
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
                .toList()

            if (songsInGroup.size <= 1) continue
            totalMerged += performSongMerge(songsInGroup)
        }

        val pathDuplicates = SongTable
            .select(SongTable.filePath)
            .groupBy(SongTable.filePath)
            .having { SongTable.id.count() greater 1L }
            .toList()

        for (duplicateGroup in pathDuplicates) {
            val songsInGroup = SongTable
                .select(SongTable.id, SongTable.inserted, SongTable.title, SongTable.explicit, SongTable.atmosPath)
                .where { SongTable.filePath eq duplicateGroup[SongTable.filePath] }
                .orderBy(SongTable.inserted, SortOrder.ASC)
                .toList()

            if (songsInGroup.size <= 1) continue
            totalMerged += performSongMerge(songsInGroup)
        }

        logger.info("Duplicate song merge completed. Total merged: $totalMerged")
        return totalMerged
    }

    private fun performSongMerge(songsInGroup: List<ResultRow>): Int {
        val keptSongRow = songsInGroup.first()
        val keptSongId = keptSongRow[SongTable.id].value
        val songsToMerge = songsInGroup.drop(1)

        val anyExplicit = songsInGroup.any { 
            it[SongTable.explicit] || it[SongTable.title].contains("\uD83C\uDD74")
        }

        val bestTitle = songsInGroup
            .map { it[SongTable.title].replace("\uD83C\uDD74", "").trim() }
            .firstOrNull { it.isNotBlank() } ?: keptSongRow[SongTable.title]

        val mergedAtmosPath = keptSongRow[SongTable.atmosPath] ?: songsToMerge.firstNotNullOfOrNull { it[SongTable.atmosPath] }

        if (bestTitle != keptSongRow[SongTable.title] || anyExplicit != keptSongRow[SongTable.explicit] || mergedAtmosPath != keptSongRow[SongTable.atmosPath]) {
            SongTable.update({ SongTable.id eq keptSongId }) {
                it[title] = bestTitle
                it[explicit] = anyExplicit
                it[atmosPath] = mergedAtmosPath
            }
        }

        logger.info("Merging ${songsToMerge.size} songs into $keptSongId")

        var mergedInGroup = 0
        for (oldSongRow in songsToMerge) {
            val oldSongId = oldSongRow[SongTable.id].value
            mergeSongReferences(oldSongId, keptSongId)
            SongTable.deleteWhere { SongTable.id eq oldSongId }
            mergedInGroup++
        }
        return mergedInGroup
    }

    suspend fun mergeSameAlbumSongs(): Int {
        logger.info("Starting same-album duplicate song merge check")

        val allSongsInAlbums = SongTable
            .select(
                SongTable.id,
                SongTable.albumId,
                SongTable.title,
                SongTable.trackNumber,
                SongTable.discNumber,
                SongTable.fileSize,
                SongTable.inserted,
                SongTable.explicit,
                SongTable.atmosPath
            )
            .toList()

        val groups = allSongsInAlbums.groupBy { row ->
            Triple(
                row[SongTable.albumId].value,
                row[SongTable.title].replace("\uD83C\uDD74", "").trim().lowercase(),
                row[SongTable.trackNumber] to row[SongTable.discNumber]
            )
        }.filter { it.value.size > 1 }

        if (groups.isEmpty()) {
            logger.info("No same-album duplicate songs found")
            return 0
        }

        logger.info("Found ${groups.size} groups of same-album duplicate songs")

        val allSongsToDelete = mutableListOf<UUID>()
        var totalMerged = 0

        for ((_, songsInGroupRows) in groups) {
            val sortedGroup = songsInGroupRows.sortedWith(
                compareByDescending<ResultRow> { it[SongTable.fileSize] }
                    .thenBy { it[SongTable.inserted] }
            )

            val keptSongRow = sortedGroup.first()
            val keptSongId = keptSongRow[SongTable.id].value
            val songsToMerge = sortedGroup.drop(1)

            val anyExplicit = songsInGroupRows.any { 
                it[SongTable.explicit] || it[SongTable.title].contains("\uD83C\uDD74")
            }
            val cleanTitle = keptSongRow[SongTable.title].replace("\uD83C\uDD74", "").trim()

            val mergedAtmosPath = keptSongRow[SongTable.atmosPath] ?: songsToMerge.firstNotNullOfOrNull { it[SongTable.atmosPath] }

            if (cleanTitle != keptSongRow[SongTable.title] || anyExplicit != keptSongRow[SongTable.explicit] || mergedAtmosPath != keptSongRow[SongTable.atmosPath]) {
                SongTable.update({ SongTable.id eq keptSongId }) {
                    it[title] = cleanTitle
                    it[explicit] = anyExplicit
                    it[atmosPath] = mergedAtmosPath
                }
            }

            logger.info("Merging ${songsToMerge.size} songs into $keptSongId")

            for (oldSongRow in songsToMerge) {
                val oldSongId = oldSongRow[SongTable.id].value
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
            val distinctMbIds = group.mapNotNull { it.getOrNull(AlbumMusicBrainzTable.musicBrainzId)?.value }.distinct()
            if (distinctMbIds.size > 1) {
                logger.warn("Found albums with same originalId $originalId but different MusicBrainz IDs: $distinctMbIds. Skipping merge.")
                continue
            }

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
                val importer = pluginManager.getAllImporters().find { it.id == originalId.substringBefore(":") }
                importer?.updateAlbumMetadata(keptAlbumId, originalId)
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

    suspend fun fixIncorrectMerges(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Int = dbQuery {
        val allAlbums = AlbumTable.leftJoin(AlbumMusicBrainzTable).selectAll().toList()
        var totalFixed = 0

        allAlbums.forEachIndexed { index, albumRow ->
            onProgress((index.toDouble() / allAlbums.size) * 100.0, "Checking album ${albumRow[AlbumTable.name]}...")

            val albumId = albumRow[AlbumTable.id].value
            val albumCover = albumRow[AlbumTable.cover]?.value
            val albumMbId = albumRow.getOrNull(AlbumMusicBrainzTable.musicBrainzId)?.value

            val songs = SongTable
                .leftJoin(SongMusicBrainzTable)
                .select(
                    SongTable.id,
                    SongTable.cover,
                    SongTable.discNumber,
                    SongTable.trackNumber,
                    SongMusicBrainzTable.musicBrainzId
                )
                .where { SongTable.albumId eq albumId }
                .toList()

            if (songs.size <= 1) return@forEachIndexed

            val identityGroups = songs.groupBy { songRow ->
                val songCover = songRow[SongTable.cover]?.value
                val songMbId = songRow.getOrNull(SongMusicBrainzTable.musicBrainzId)?.value

                val belongs = if (songMbId != null && albumMbId != null) {
                    MBRecordingReleaseTable.selectAll()
                        .where { (MBRecordingReleaseTable.recordingId eq songMbId) and (MBRecordingReleaseTable.releaseId eq albumMbId) }
                        .any()
                } else {
                    true
                }
                
                val primaryOtherRelease = if (songMbId != null && !belongs) {
                    MBRecordingReleaseTable.select(MBRecordingReleaseTable.releaseId)
                        .where { MBRecordingReleaseTable.recordingId eq songMbId }
                        .firstOrNull()?.get(MBRecordingReleaseTable.releaseId)?.value
                } else null

                Triple(songCover, belongs, primaryOtherRelease)
            }

            val finalGroups = mutableListOf<Pair<Triple<UUID?, Boolean, UUID?>, List<ResultRow>>>()
            identityGroups.forEach { (identity, groupSongs) ->
                val subgroups = mutableListOf<MutableList<ResultRow>>()
                groupSongs.forEach { song ->
                    val pos = song[SongTable.discNumber] to song[SongTable.trackNumber]
                    val targetSubgroup = subgroups.find { sub ->
                        sub.none { it[SongTable.discNumber] == pos.first && it[SongTable.trackNumber] == pos.second }
                    }
                    if (targetSubgroup != null) {
                        targetSubgroup.add(song)
                    } else {
                        subgroups.add(mutableListOf(song))
                    }
                }
                subgroups.forEach { finalGroups.add(identity to it) }
            }

            if (finalGroups.size > 1) {
                val targetGroupIndex = finalGroups.indices.find { i ->
                    val (identity, _) = finalGroups[i]
                    identity.first == albumCover && identity.second
                } ?: finalGroups.indices.find { i -> finalGroups[i].first.second }
                  ?: finalGroups.indices.find { i -> finalGroups[i].first.first == albumCover }
                  ?: finalGroups.indices.maxBy { finalGroups[it].second.size }

                val targetIdentity = finalGroups[targetGroupIndex].first

                finalGroups.filterIndexed { i, _ -> i != targetGroupIndex }.forEach { (identity, groupSongs) ->
                    val (groupCover, belongsToAlbum, suggestedMbId) = identity
                    
                    if (groupCover != albumCover || !belongsToAlbum || identity == targetIdentity) {
                        logger.info("Splitting ${groupSongs.size} songs from album ${albumRow[AlbumTable.name]} ($albumId) - Identity match: $belongsToAlbum, Cover match: ${groupCover == albumCover}, Collision: ${identity == targetIdentity}")
                        splitSongsToNewAlbum(albumRow, groupSongs, suggestedMbId)
                        totalFixed++
                    }
                }
            }
        }
        totalFixed
    }

    private suspend fun splitSongsToNewAlbum(originalAlbum: ResultRow, songs: List<ResultRow>, suggestedMbId: UUID?) {
        val originalAlbumId = originalAlbum[AlbumTable.id].value

        val existingAlbumId = suggestedMbId?.let { mbId ->
            AlbumMusicBrainzTable.selectAll()
                .where { AlbumMusicBrainzTable.musicBrainzId eq mbId }
                .firstOrNull()?.get(AlbumMusicBrainzTable.albumId)?.value
        }

        if (existingAlbumId != null) {
            val songIds = songs.map { it[SongTable.id].value }
            SongTable.update({ SongTable.id inList songIds }) {
                it[albumId] = EntityID(existingAlbumId, AlbumTable)
            }

            val remainingCount = SongTable.selectAll().where { SongTable.albumId eq originalAlbumId }.count().toInt()
            AlbumTable.update({ AlbumTable.id eq originalAlbumId }) {
                it[songCount] = remainingCount
            }

            val newTargetCount = SongTable.selectAll().where { SongTable.albumId eq existingAlbumId }.count().toInt()
            AlbumTable.update({ AlbumTable.id eq existingAlbumId }) {
                it[songCount] = newTargetCount
            }

            get<AlbumService>().syncAlbumSongsWithMusicBrainz(existingAlbumId, suggestedMbId)
            return
        }

        val newAlbumId = UUID.randomUUID()
        val mbRelease = suggestedMbId?.let { mbId ->
            MBReleaseTable.selectAll().where { MBReleaseTable.id eq mbId }.singleOrNull()
        }
        val mbTrackCount = suggestedMbId?.let { mbId ->
            MBMediaTable.select(MBMediaTable.trackCount.sum())
                .where { MBMediaTable.releaseId eq mbId }
                .firstOrNull()?.get(MBMediaTable.trackCount.sum())
        }

        val firstSong = songs.first()
        val newCover = firstSong[SongTable.cover]?.value

        AlbumTable.insert { album ->
            album[id] = EntityID(newAlbumId, AlbumTable)
            album[name] = mbRelease?.get(MBReleaseTable.title) ?: originalAlbum[AlbumTable.name]
            album[releaseDate] = mbRelease?.get(MBReleaseTable.date) ?: originalAlbum[AlbumTable.releaseDate]
            album[songCount] = mbTrackCount?.takeIf { it > 0 } ?: songs.size
            album[cover] = newCover?.let { id -> EntityID(id, ImageTable) }
            album[originalId] = null
        }

        if (suggestedMbId != null) {
            AlbumMusicBrainzTable.insert {
                it[albumId] = EntityID(newAlbumId, AlbumTable)
                it[musicBrainzId] = EntityID(suggestedMbId, MBReleaseTable)
            }
        }

        val artists = AlbumArtistTable.select(AlbumArtistTable.artistId).where { AlbumArtistTable.albumId eq originalAlbumId }.toList()
        AlbumArtistTable.batchInsert(artists) { row ->
            this[AlbumArtistTable.albumId] = EntityID(newAlbumId, AlbumTable)
            this[AlbumArtistTable.artistId] = row[AlbumArtistTable.artistId]
        }

        val genres = AlbumGenreTable.select(AlbumGenreTable.genreId).where { AlbumGenreTable.albumId eq originalAlbumId }.toList()
        AlbumGenreTable.batchInsert(genres) { row ->
            this[AlbumGenreTable.albumId] = EntityID(newAlbumId, AlbumTable)
            this[AlbumGenreTable.genreId] = row[AlbumGenreTable.genreId]
        }

        val songIds = songs.map { it[SongTable.id].value }
        SongTable.update({ SongTable.id inList songIds }) {
            it[albumId] = EntityID(newAlbumId, AlbumTable)
        }

        val remainingCount = SongTable.selectAll().where { SongTable.albumId eq originalAlbumId }.count().toInt()
        AlbumTable.update({ AlbumTable.id eq originalAlbumId }) {
            it[songCount] = remainingCount
        }

        if (suggestedMbId != null) {
            get<AlbumService>().syncAlbumSongsWithMusicBrainz(newAlbumId, suggestedMbId)
        }
    }

    private fun calculateSimilarity(albumA: ResultRow, albumB: ResultRow, artistsA: List<UUID>, artistsB: List<UUID>): Int {
        val mbIdA = albumA.getOrNull(AlbumMusicBrainzTable.musicBrainzId)?.value
        val mbIdB = albumB.getOrNull(AlbumMusicBrainzTable.musicBrainzId)?.value
        if (mbIdA != null && mbIdB != null && mbIdA != mbIdB) return 0

        val coverA = albumA.getOrNull(AlbumTable.cover)?.value
        val coverB = albumB.getOrNull(AlbumTable.cover)?.value
        if (coverA != null && coverB != null && coverA != coverB) return 0

        var score = 0

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

        val providersForOld = AlbumProviderTable.selectAll().where { AlbumProviderTable.albumId eq oldAlbumId }.toList()
        val providersForKept = AlbumProviderTable.selectAll().where { AlbumProviderTable.albumId eq keptAlbumId }
            .map { it[AlbumProviderTable.provider] to it[AlbumProviderTable.externalId] }.toSet()

        for (row in providersForOld) {
            val provider = row[AlbumProviderTable.provider]
            val externalId = row[AlbumProviderTable.externalId]
            if (provider to externalId !in providersForKept) {
                AlbumProviderTable.update({
                    (AlbumProviderTable.albumId eq oldAlbumId) and
                            (AlbumProviderTable.provider eq provider) and
                            (AlbumProviderTable.externalId eq externalId)
                }) {
                    it[AlbumProviderTable.albumId] = keptAlbumId
                }
            }
        }
        AlbumProviderTable.deleteWhere { AlbumProviderTable.albumId eq oldAlbumId }

        val collectionsForOldAlbum = CollectionAlbumTable.select(CollectionAlbumTable.collectionId).where { CollectionAlbumTable.albumId eq oldAlbumId }.map { it[CollectionAlbumTable.collectionId].value }
        val collectionsForKeptAlbum = CollectionAlbumTable.select(CollectionAlbumTable.collectionId).where { CollectionAlbumTable.albumId eq keptAlbumId }.map { it[CollectionAlbumTable.collectionId].value }.toSet()

        for (collectionId in collectionsForOldAlbum) {
            if (collectionId !in collectionsForKeptAlbum) {
                CollectionAlbumTable.update({ (CollectionAlbumTable.albumId eq oldAlbumId) and (CollectionAlbumTable.collectionId eq collectionId) }) {
                    it[CollectionAlbumTable.albumId] = keptAlbumId
                }
            }
        }
        CollectionAlbumTable.deleteWhere { CollectionAlbumTable.albumId eq oldAlbumId }
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

        val providersForOld = SongProviderTable.selectAll().where { SongProviderTable.songId eq oldSongId }.toList()
        val providersForKept = SongProviderTable.selectAll().where { SongProviderTable.songId eq keptSongId }
            .map { it[SongProviderTable.provider] to it[SongProviderTable.externalId] }.toSet()

        for (row in providersForOld) {
            val provider = row[SongProviderTable.provider]
            val externalId = row[SongProviderTable.externalId]
            if (provider to externalId !in providersForKept) {
                SongProviderTable.update({
                    (SongProviderTable.songId eq oldSongId) and
                            (SongProviderTable.provider eq provider) and
                            (SongProviderTable.externalId eq externalId)
                }) {
                    it[SongProviderTable.songId] = keptSongId
                }
            }
        }
        SongProviderTable.deleteWhere { SongProviderTable.songId eq oldSongId }

        val collectionsForOldSong = CollectionSongTable.select(CollectionSongTable.collectionId).where { CollectionSongTable.songId eq oldSongId }.map { it[CollectionSongTable.collectionId].value }
        val collectionsForKeptSong = CollectionSongTable.select(CollectionSongTable.collectionId).where { CollectionSongTable.songId eq keptSongId }.map { it[CollectionSongTable.collectionId].value }.toSet()

        for (collectionId in collectionsForOldSong) {
            if (collectionId !in collectionsForKeptSong) {
                CollectionSongTable.update({ (CollectionSongTable.songId eq oldSongId) and (CollectionSongTable.collectionId eq collectionId) }) {
                    it[CollectionSongTable.songId] = keptSongId
                }
            }
        }
        CollectionSongTable.deleteWhere { CollectionSongTable.songId eq oldSongId }
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
