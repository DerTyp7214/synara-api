package dev.dertyp.services

import dev.dertyp.data.ProxyInfo
import dev.dertyp.data.ServerStats
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.server.BuildConfig
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

class ServerStatsService(
    private val storageService: StorageService,
    private val reverseProxyService: ReverseProxyService,
) : IServerStatsService, Service() {
    override suspend fun getStats(): ServerStats = dbQuery {
        val songCount = SongTable.selectAll().count().toInt()
        val albumCount = AlbumTable.selectAll().count().toInt()
        val artistCount = ArtistTable.selectAll().count().toInt()
        val imagesCount = ImageTable.selectAll().count().toInt()
        val playlistCount = PlaylistTable.selectAll().count().toInt()

        val indexedFileSize = SongTable.fileSize.sum().let {
            SongTable.select(it).singleOrNull()?.get(it)
        } ?: 0L

        val totalDuration = SongTable.duration.sum().let {
            SongTable.select(it).singleOrNull()?.get(it)
        } ?: 0L

        val osName = System.getProperty("os.name")
        val osVersion = System.getProperty("os.version")
        val osArch = System.getProperty("os.arch")

        ServerStats(
            songCount = songCount,
            albumCount = albumCount,
            artistCount = artistCount,
            imagesCount = imagesCount,
            playlistCount = playlistCount,
            totalFileSize = storageService.getTotalStorage(),
            indexedFileSize = indexedFileSize,
            averageSizePerSong = if (songCount > 0) indexedFileSize / songCount else 0L,
            totalDuration = totalDuration,
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
