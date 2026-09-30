package dev.dertyp.services

import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.db.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.random.Random

class SongQueryFixture(
    val userId: UUID,
    val songIds: List<UUID>,
    val artistIds: List<UUID>,
    val albumIds: List<UUID>,
    val playlistId: UUID,
    val userPlaylistId: UUID,
    val collectionId: UUID,
    val recordingIds: List<UUID>,
) {
    companion object {
        val tables = arrayOf(
            ArtistTable, AlbumTable, SongTable, SongVariantTable, SongArtistTable,
            SongMusicBrainzTable, SongAudioDataTable, ImageTable, AnimatedImageTable, GenreTable,
            UserTable, AlbumMusicBrainzTable, ArtistMusicBrainzTable,
            ArtistAliasTable, ArtistMemberTable, AlbumArtistTable,
            PlaylistTable, UserSongTable, TimecodeTagTable, UserPlaylistTable,
            SongGenreTable, ArtistGenreTable, AlbumGenreTable,
            PlaylistSongTable, UserPlaylistSongTable,
            SyncedLyricsTable, ImageMetadataTable, RecentReleaseTable,
            FollowedArtistTable, TranscodedSongTable, CustomMigrationTable,
            ScheduledTaskLogTable, ArtistSplitAliasTable, SyncServiceTable,
            SongProviderTable, AlbumProviderTable, SearchIndexQueueTable,
            CollectionTable, CollectionSongTable, CollectionAlbumTable, CollectionArtistTable, CollectionPlaylistTable,
            *allMusicBrainzTables
        )

        private class EdgeSong(val title: String, val releaseDate: String?, val explicit: Boolean, val album: Int, val inserted: Long)

        private val edgeSongs = listOf(
            EdgeSong("Edge A\t", "2021", false, 0, 1_000_001L),
            EdgeSong("Edge A \uD83C\uDD74", "2021-01-01", true, 0, 1_000_000L),
            EdgeSong("\u00A0Edge B", "2022-05", false, 1, 1_000_000L),
            EdgeSong("Edge B", null, false, 1, 1_000_000L),
            EdgeSong("Edge C\uD83C\uDD74\uD83C\uDD74", "2023-03-03", true, 2, 1_000_002L),
            EdgeSong("Edge C\uD83C\uDD74", "2023-03-03", false, 2, 1_000_001L),
            EdgeSong("Edge D", "2024-04-04", false, 3, 1_000_000L),
            EdgeSong("Edge D", "2024-04-05", false, 3, 1_000_000L),
            EdgeSong("Edge E", "2023-02-29", false, 4, 1_000_001L),
            EdgeSong("Edge E\u2028", null, false, 4, 1_000_000L),
            EdgeSong("Edge F\u3000", "1900-02-29", true, 5, 1_000_000L),
            EdgeSong("Edge F", "", false, 5, 1_000_000L),
            EdgeSong("Edge G", "2000-02-29", false, 0, 1_000_001L),
            EdgeSong("Edge G\u2003\uD83C\uDD74", "2000-02-29", true, 0, 1_000_001L),
            EdgeSong("Edge H", "abcd", false, 1, 1_000_002L),
            EdgeSong("Edge H", "  ", false, 1, 1_000_001L),
            EdgeSong("Edge I", "2024-02-29", false, 2, 1_000_000L),
            EdgeSong("Edge I", "2024-2-29", false, 2, 1_000_001L),
            EdgeSong("Edge J \uD83C\uDD74 ", "2019-12-31", true, 3, 1_000_000L),
            EdgeSong("Edge J", "2019-12-31", false, 3, 1_000_000L),
            EdgeSong("Edge K", "2019", false, 4, 1_000_000L),
            EdgeSong("Edge K", "2019-01-02", false, 4, 1_000_000L),
        )

        fun build(database: Database, seed: Int, songCount: Int, duplicates: Boolean = true): SongQueryFixture {
            val random = Random(seed)
            fun uuid() = UUID(random.nextLong(), random.nextLong())

            val userId = uuid()
            val artistCount = maxOf(8, songCount / 20)
            val albumCount = maxOf(6, songCount / 10)
            val genreNames = listOf("rock", "Rock", "Électro", "ambient", "Zydeco", "b-side", "B side")
            val genreIds = List(genreNames.size) { uuid() }
            val artistIds = List(artistCount) { uuid() }
            val albumIds = List(albumCount) { uuid() }
            val songIds = List(songCount) { uuid() }
            val insertedTimes = List(songCount) { 1_000_000L + random.nextInt(3) }
            val edgeSongIds = if (duplicates) List(edgeSongs.size) { uuid() } else emptyList()
            val playlistId = uuid()
            val userPlaylistId = uuid()
            val collectionId = uuid()
            val recordingIds = mutableListOf<UUID>()

            transaction(database) {
                UserTable.insert {
                    it[id] = userId
                    it[username] = "user$seed"
                    it[passwordHash] = ""
                }

                GenreTable.batchInsert(genreIds.withIndex()) { (index, genreId) ->
                    this[GenreTable.id] = genreId
                    this[GenreTable.name] = genreNames[index]
                }

                val imageRows = mutableListOf<Pair<UUID, String?>>()
                fun image(blurHash: String?): UUID = uuid().also { imageRows += it to blurHash }

                val artistImages = artistIds.indices.associateWith { a -> if (a % 2 == 0) image("artist-bh-$a") else null }
                val albumImages = albumIds.indices.associateWith { b -> if (b % 4 != 3) image(if (b % 3 == 0) null else "album-bh-$b") else null }
                val songImages = songIds.indices.associateWith { i -> if (i % 3 != 0) image("song-bh-$i") else null }
                val songFrames = songIds.indices.filter { it % 7 == 0 }.associateWith { i -> image("song-frame-$i") }
                val albumFrames = albumIds.indices.filter { it % 3 == 0 }.associateWith { b -> image("album-frame-$b") }

                ImageTable.batchInsert(imageRows) { (imageId, blurHash) ->
                    this[ImageTable.id] = imageId
                    this[ImageTable.path] = "/images/$imageId"
                    this[ImageTable.imageHash] = imageId.toString()
                    this[ImageTable.origin] = "test"
                    this[ImageTable.blurHash] = blurHash
                }

                ImageMetadataTable.batchInsert(songImages.entries.filter { it.value != null }) { (i, imageId) ->
                    this[ImageMetadataTable.imageId] = imageId!!
                    this[ImageMetadataTable.width] = 100
                    this[ImageMetadataTable.height] = 100
                    this[ImageMetadataTable.byteSize] = 1000L
                    this[ImageMetadataTable.primaryColor] = 0
                    this[ImageMetadataTable.red] = 0
                    this[ImageMetadataTable.green] = 0
                    this[ImageMetadataTable.blue] = 0
                    this[ImageMetadataTable.luminance] = 0.5
                    this[ImageMetadataTable.labL] = 50.0 + (i % 5)
                    this[ImageMetadataTable.labA] = (i % 3).toDouble()
                    this[ImageMetadataTable.labB] = 0.0
                }

                val songAnimated = songFrames.mapValues { uuid() }
                val albumAnimated = albumFrames.mapValues { uuid() }
                AnimatedImageTable.batchInsert(songAnimated.entries.map { (i, animatedId) -> animatedId to songFrames[i]!! } +
                        albumAnimated.entries.map { (b, animatedId) -> animatedId to albumFrames[b]!! }) { (animatedId, frameId) ->
                    this[AnimatedImageTable.id] = animatedId
                    this[AnimatedImageTable.path] = "/animated/$animatedId"
                    this[AnimatedImageTable.contentHash] = animatedId.toString()
                    this[AnimatedImageTable.origin] = "test"
                    this[AnimatedImageTable.imageId] = frameId
                }

                ArtistTable.batchInsert(artistIds.withIndex()) { (a, artistId) ->
                    this[ArtistTable.id] = artistId
                    this[ArtistTable.name] = "Artist $a"
                    this[ArtistTable.isGroup] = a % 4 == 0
                    this[ArtistTable.about] = "About $a"
                    this[ArtistTable.image] = artistImages[a]
                }

                val mbArtistIds = artistIds.indices.filter { it % 3 == 0 }.associateWith { uuid() }
                MBArtistTable.batchInsert(mbArtistIds.entries) { (a, mbId) ->
                    this[MBArtistTable.id] = mbId
                    this[MBArtistTable.name] = "MB Artist $a"
                    this[MBArtistTable.sortName] = "MB Artist $a"
                }
                ArtistMusicBrainzTable.batchInsert(mbArtistIds.entries) { (a, mbId) ->
                    this[ArtistMusicBrainzTable.artistId] = artistIds[a]
                    this[ArtistMusicBrainzTable.musicBrainzId] = mbId
                }

                val memberRows = artistIds.indices.filter { it % 4 == 0 }.flatMap { group ->
                    listOf(group + 1, group + 2).filter { it < artistCount }.map { member -> artistIds[member] to artistIds[group] }
                } + listOf(artistIds[1] to artistIds[4])
                ArtistMemberTable.batchInsert(memberRows.distinct()) { (member, group) ->
                    this[ArtistMemberTable.artistId] = member
                    this[ArtistMemberTable.groupId] = group
                }

                val aliasIds = artistIds.indices.filter { it % 3 == 1 }.associateWith { listOf(uuid(), uuid()) }
                ArtistAliasTable.batchInsert(aliasIds.entries.flatMap { (a, ids) -> ids.mapIndexed { n, aliasId -> Triple(a, n, aliasId) } }) { (a, n, aliasId) ->
                    this[ArtistAliasTable.id] = aliasId
                    this[ArtistAliasTable.artistId] = artistIds[a]
                    this[ArtistAliasTable.name] = "Alias $a-$n"
                }

                FollowedArtistTable.batchInsert(artistIds.indices.filter { it % 3 == 0 }) { a ->
                    this[FollowedArtistTable.userId] = userId
                    this[FollowedArtistTable.artistId] = artistIds[a]
                }

                AlbumTable.batchInsert(albumIds.withIndex()) { (b, albumId) ->
                    this[AlbumTable.id] = albumId
                    this[AlbumTable.name] = listOf("Album", "album", "\u00C1lbum", "ALBUM")[b % 4] + " $b"
                    this[AlbumTable.releaseDate] = "20${10 + b % 5}-01-01"
                    this[AlbumTable.songCount] = 10
                    this[AlbumTable.cover] = albumImages[b]
                    this[AlbumTable.animatedCover] = albumAnimated[b]
                    this[AlbumTable.originalId] = if (b % 2 == 0) "album-orig-$b" else null
                    this[AlbumTable.barcode] = if (b % 3 == 0) "barcode$b" else null
                }

                val mbReleaseIds = albumIds.indices.filter { it % 2 == 0 }.associateWith { uuid() }
                MBReleaseTable.batchInsert(mbReleaseIds.entries) { (b, mbId) ->
                    this[MBReleaseTable.id] = mbId
                    this[MBReleaseTable.title] = "MB Release $b"
                }
                AlbumMusicBrainzTable.batchInsert(mbReleaseIds.entries) { (b, mbId) ->
                    this[AlbumMusicBrainzTable.albumId] = albumIds[b]
                    this[AlbumMusicBrainzTable.musicBrainzId] = mbId
                }

                val albumArtistRows = albumIds.indices.filter { it % 5 != 4 }.flatMap { b ->
                    val first = (b * 3 + 1) % artistCount
                    val second = (b * 3 + 2) % artistCount
                    val third = (b * 3 + 5) % artistCount
                    val artists = listOfNotNull(first, if (b % 2 == 0) second else null, if (b % 3 == 0) third else null).distinct()
                    val byIdString = artists.sortedBy { artistIds[it].toString() }
                    artists.mapIndexed { j, a -> Triple(b, a, j to byIdString.indexOf(a)) }
                }
                AlbumArtistTable.batchInsert(albumArtistRows) { (b, a, slot) ->
                    val (j, idRank) = slot
                    val count = albumArtistRows.count { it.first == b }
                    this[AlbumArtistTable.albumId] = albumIds[b]
                    this[AlbumArtistTable.artistId] = artistIds[a]
                    this[AlbumArtistTable.creditedAliasId] = if (b % 2 == 1) aliasIds[a]?.first() else null
                    this[AlbumArtistTable.position] = when (b % 3) {
                        0 -> 0
                        1 -> j
                        else -> count - 1 - idRank
                    }
                    this[AlbumArtistTable.joinPhrase] = if (b % 2 == 0 && j < count - 1) " / " else null
                }

                SongTable.batchInsert(songIds.withIndex()) { (i, songId) ->
                    val pair = if (duplicates) i / 2 else i
                    this[SongTable.id] = songId
                    this[SongTable.title] = if (i % 16 == 3) "Song $pair 🅴" else "Song $pair"
                    this[SongTable.titleTags] = if (i % 13 == 0) encodeTitleTags(listOf(TitleTag(TitleTagKind.LIVE, "Live"))) else "[]"
                    this[SongTable.albumId] = albumIds[pair % albumCount]
                    this[SongTable.duration] = 180000L + (pair % 7) * 1000L
                    this[SongTable.releaseDate] = "2020-0${pair % 9 + 1}-01"
                    this[SongTable.lyrics] = if (i % 4 == 0) "la la lyric $i" else ""
                    this[SongTable.explicit] = duplicates && i % 4 == 1
                    this[SongTable.filePath] = "/music/song_$i.flac"
                    this[SongTable.cover] = songImages[i]
                    this[SongTable.animatedCover] = songAnimated[i]
                    this[SongTable.originalUrl] = if (i % 2 == 0) "https://example.com/track/$i" else ""
                    this[SongTable.isrc] = if (i % 3 == 0) "USABC${1000000 + i}" else null
                    this[SongTable.trackNumber] = pair % 12 + 1
                    this[SongTable.discNumber] = 1 + (pair % 17) / 16
                    this[SongTable.copyright] = "(c) $i"
                    this[SongTable.sampleRate] = if (i % 2 == 0) 44100 else 96000
                    this[SongTable.bitsPerSample] = if (i % 3 == 0) 24 else 16
                    this[SongTable.bitRate] = 1000L * i
                    this[SongTable.fileSize] = 10000L + i
                    this[SongTable.audioStartMs] = if (i % 9 == 0) 250L else null
                    this[SongTable.channels] = 2
                    this[SongTable.inserted] = insertedTimes[i]
                }

                if (duplicates) {
                    SongTable.batchInsert(edgeSongs.zip(edgeSongIds)) { (edge, songId) ->
                        this[SongTable.id] = songId
                        this[SongTable.title] = edge.title
                        this[SongTable.albumId] = albumIds[edge.album]
                        this[SongTable.duration] = 200000L
                        this[SongTable.releaseDate] = edge.releaseDate
                        this[SongTable.explicit] = edge.explicit
                        this[SongTable.filePath] = "/music/edge_$songId.flac"
                        this[SongTable.originalUrl] = ""
                        this[SongTable.trackNumber] = 90
                        this[SongTable.discNumber] = 1
                        this[SongTable.inserted] = edge.inserted
                    }
                }

                val songArtistRows = songIds.indices.flatMap { i ->
                    val artists = (0..(i % 4)).map { j -> (i + j * 5) % artistCount }.distinct()
                    artists.mapIndexed { j, a -> Triple(i, a, j to artists.size) }
                }
                SongArtistTable.batchInsert(songArtistRows) { (i, a, slot) ->
                    val (j, count) = slot
                    this[SongArtistTable.songId] = songIds[i]
                    this[SongArtistTable.artistId] = artistIds[a]
                    this[SongArtistTable.creditedAliasId] = if (i % 2 == 0) aliasIds[a]?.last() else null
                    this[SongArtistTable.position] = when (i % 4) {
                        0 -> 0
                        1 -> count - 1 - j
                        2 -> if (j == 1) 0 else 5
                        else -> j
                    }
                    this[SongArtistTable.joinPhrase] = when (i % 4) {
                        1 -> if (j < count - 1) " & " else null
                        2 -> if (j == 1) " feat. " else null
                        else -> null
                    }
                }

                SongGenreTable.batchInsert(songIds.indices.flatMap { i -> (0 until i % 5).map { g -> i to (i + g * 2) % genreIds.size }.distinct() }) { (i, g) ->
                    this[SongGenreTable.songId] = songIds[i]
                    this[SongGenreTable.genreId] = genreIds[g]
                }

                val providerRows = songIds.indices.flatMap { i ->
                    val originalUrl = if (i % 2 == 0) "https://example.com/track/$i" else ""
                    (0 until i % 3).map { p ->
                        val provider = if (p == 0) "tidal" else "qobuz"
                        val externalId = "${i * 7 + p}"
                        Triple(i, provider to externalId, "https://$provider.example/track/$externalId")
                    } + listOfNotNull(
                        if (i % 5 == 2 && originalUrl.isNotEmpty()) Triple(i, "zzz" to "orig-$i", originalUrl) else null,
                        if (i % 6 == 1) Triple(i, "Tidal" to "x$i", "https://Tidal.example/track/x$i") else null,
                    ) + if (i % 7 == 3) listOf("9", "10").map { externalId ->
                        Triple(i, "qobuz" to externalId, "https://qobuz.example/track/$i/$externalId")
                    } else emptyList()
                }
                SongProviderTable.batchInsert(providerRows) { (i, key, rawUrl) ->
                    this[SongProviderTable.songId] = songIds[i]
                    this[SongProviderTable.provider] = key.first
                    this[SongProviderTable.externalId] = key.second
                    this[SongProviderTable.type] = "song"
                    this[SongProviderTable.rawUrl] = rawUrl
                }

                val mbRecordings = songIds.indices.filter { it % 5 == 0 }.associateWith { uuid() }
                recordingIds += mbRecordings.values
                MBRecordingTable.batchInsert(mbRecordings.entries) { (i, mbId) ->
                    this[MBRecordingTable.id] = mbId
                    this[MBRecordingTable.title] = "Recording $i"
                }
                SongMusicBrainzTable.batchInsert(mbRecordings.entries) { (i, mbId) ->
                    this[SongMusicBrainzTable.songId] = songIds[i]
                    this[SongMusicBrainzTable.musicBrainzId] = mbId
                }
                val sharedRecording = mbRecordings.values.first()
                SongMusicBrainzTable.batchInsert(songIds.indices.filter { it % 5 == 1 && it < 20 }) { i ->
                    this[SongMusicBrainzTable.songId] = songIds[i]
                    this[SongMusicBrainzTable.musicBrainzId] = sharedRecording
                }

                UserSongTable.batchInsert(songIds.indices.filter { it % 3 == 0 || it % 6 == 4 }) { i ->
                    this[UserSongTable.userId] = userId
                    this[UserSongTable.songId] = songIds[i]
                    this[UserSongTable.isFavourite] = i % 3 == 0
                    this[UserSongTable.superLikedAt] = if (i % 6 == 0) 7000L + i / 12 else null
                    this[UserSongTable.createdAt] = 4000L + i
                    this[UserSongTable.updatedAt] = 5000L + i / 4
                }

                SongVariantTable.batchInsert(songIds.indices.filter { it % 11 == 0 }) { i ->
                    this[SongVariantTable.songId] = songIds[i]
                    this[SongVariantTable.kind] = SongVariantKind.ATMOS
                    this[SongVariantTable.path] = "/atmos/$i.m4a"
                    this[SongVariantTable.codec] = "eac3"
                }

                SyncedLyricsTable.batchInsert(songIds.indices.filter { it % 6 == 2 }) { i ->
                    this[SyncedLyricsTable.songId] = songIds[i]
                    this[SyncedLyricsTable.rawLyrics] = "la synced words $i"
                }

                PlaylistTable.insert {
                    it[id] = playlistId
                    it[name] = "Playlist"
                }
                PlaylistSongTable.batchInsert(songIds.indices.filter { it % 2 == 0 }) { i ->
                    this[PlaylistSongTable.playlistId] = playlistId
                    this[PlaylistSongTable.songId] = songIds[i]
                    this[PlaylistSongTable.position] = i % 5
                }

                UserPlaylistTable.insert {
                    it[id] = userPlaylistId
                    it[name] = "User playlist"
                    it[description] = ""
                    it[creator] = userId
                }
                UserPlaylistSongTable.batchInsert(songIds.indices.filter { it % 3 == 1 }.map { it to 100L + it / 6 } + (if (duplicates) listOf(0 to 50L, 0 to 150L) else emptyList())) { (i, addedAt) ->
                    this[UserPlaylistSongTable.playlistId] = userPlaylistId
                    this[UserPlaylistSongTable.songId] = songIds[i]
                    this[UserPlaylistSongTable.addedAt] = addedAt
                    this[UserPlaylistSongTable.id] = uuid()
                }

                CollectionTable.insert {
                    it[id] = collectionId
                    it[name] = "Collection"
                    it[creator] = userId
                }
                CollectionSongTable.batchInsert(songIds.indices.filter { it % 4 == 2 }) { i ->
                    this[CollectionSongTable.collectionId] = collectionId
                    this[CollectionSongTable.songId] = songIds[i]
                }
                CollectionArtistTable.insert {
                    it[this.collectionId] = collectionId
                    it[artistId] = artistIds[2]
                }
            }

            return SongQueryFixture(userId, songIds + edgeSongIds, artistIds, albumIds, playlistId, userPlaylistId, collectionId, recordingIds)
        }
    }
}
