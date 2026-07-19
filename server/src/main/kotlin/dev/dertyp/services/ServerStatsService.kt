package dev.dertyp.services

import dev.dertyp.data.ProxyInfo
import dev.dertyp.data.ServerStats
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.metadata.MusicBrainzCacheService
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

class ServerStatsService(
    private val storageService: StorageService,
    private val reverseProxyService: ReverseProxyService,
    private val musicBrainzCacheService: MusicBrainzCacheService,
) : IServerStatsService, Service() {
    private data class DbStats(
        val songCount: Int,
        val albumCount: Int,
        val artistCount: Int,
        val imagesCount: Int,
        val animatedImagesCount: Int,
        val playlistCount: Int,
        val indexedFileSize: Long,
        val totalDuration: Long,
        val transcodeStats: List<ServerStats.TranscodeStats>,
    )

    override suspend fun getStats(): ServerStats {
        val dbStats = dbQuery {
            val songCount = SongTable.selectAll().count().toInt()
            val albumCount = AlbumTable.selectAll().count().toInt()
            val artistCount = ArtistTable.selectAll().count().toInt()
            val imagesCount = ImageTable.selectAll().count().toInt()
            val animatedImagesCount = AnimatedImageTable.selectAll().count().toInt()
            val playlistCount =
                PlaylistTable.selectAll().count().toInt() + UserPlaylistTable.selectAll().count().toInt()

            val indexedFileSize = SongTable.fileSize.sum().let {
                SongTable.select(it).singleOrNull()?.get(it)
            } ?: 0L

            val totalDuration = SongTable.duration.sum().let {
                SongTable.select(it).singleOrNull()?.get(it)
            } ?: 0L

            val transcodeStats = TranscodedSongTable
                .select(
                    TranscodedSongTable.bitrate,
                    TranscodedSongTable.format,
                    TranscodedSongTable.bitrate.count(),
                    TranscodedSongTable.fileSize.sum()
                )
                .groupBy(TranscodedSongTable.bitrate, TranscodedSongTable.format)
                .map {
                    ServerStats.TranscodeStats(
                        bitrate = it[TranscodedSongTable.bitrate],
                        format = it[TranscodedSongTable.format],
                        count = it[TranscodedSongTable.bitrate.count()].toInt(),
                        totalSize = it[TranscodedSongTable.fileSize.sum()] ?: 0L
                    )
                }

            DbStats(
                songCount = songCount,
                albumCount = albumCount,
                artistCount = artistCount,
                imagesCount = imagesCount,
                animatedImagesCount = animatedImagesCount,
                playlistCount = playlistCount,
                indexedFileSize = indexedFileSize,
                totalDuration = totalDuration,
                transcodeStats = transcodeStats
            )
        }

        val osName = System.getProperty("os.name")
        val osVersion = System.getProperty("os.version")
        val osArch = System.getProperty("os.arch")

        return ServerStats(
            songCount = dbStats.songCount,
            albumCount = dbStats.albumCount,
            artistCount = dbStats.artistCount,
            imagesCount = dbStats.imagesCount,
            animatedImagesCount = dbStats.animatedImagesCount,
            playlistCount = dbStats.playlistCount,
            totalFileSize = storageService.getTotalStorage(),
            indexedFileSize = dbStats.indexedFileSize,
            imagesFileSize = storageService.getImagesStorage(),
            animatedImagesFileSize = storageService.getAnimatedImagesStorage(),
            averageSizePerSong = if (dbStats.songCount > 0) dbStats.indexedFileSize / dbStats.songCount else 0L,
            totalDuration = dbStats.totalDuration,
            transcodeStats = dbStats.transcodeStats,
            musicBrainzCache = musicBrainzCacheService.getStats(),
            version = ServerStats.Version(
                version = BuildConfig.VERSION,
                buildTime = BuildConfig.BUILD_TIME,
                commitHash = BuildConfig.GIT_HASH,
                runtime = "$osName ($osArch)",
                kernel = osVersion
            )
        )
    }

    override suspend fun health(): Boolean = true

    override suspend fun getProxyInfo(): ProxyInfo? {
        val host = reverseProxyService.proxyHost ?: return null
        val port = reverseProxyService.controlPort ?: return null
        return ProxyInfo(
            host = host,
            controlPort = port,
            ssl = reverseProxyService.proxySsl,
            id = reverseProxyService.proxyId
        )
    }
}
