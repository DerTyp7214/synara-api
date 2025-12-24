package dev.dertyp.data

import kotlinx.serialization.Serializable

@Serializable
data class ServerStats(
    val songCount: Int,
    val albumCount: Int,
    val artistCount: Int,
    val imagesCount: Int,
    val playlistCount: Int,
    val totalFileSize: Long,
    val indexedFileSize: Long,
    val averageSizePerSong: Long,
    val totalDuration: Long,
    val version: Version
) {
    @Serializable
    data class Version(
        val version: String,
        val buildTime: String,
        val commitHash: String,
        val runtime: String,
        val kernel: String,
    )
}
