package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import java.time.Instant

object RadioChannelTable : UUIDTable("radioChannel") {
    val name = text("name")
    val description = text("description").nullable()
    val imageId = reference("imageId", ImageTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val enabled = bool("enabled").default(false)
    val position = integer("position").default(0)
    val discovery = bool("discovery").default(false)
    val createdBy = reference("createdBy", UserTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }
}

object RadioChannelSongTable : Table("radioChannelSong") {
    val channelId = reference("channelId", RadioChannelTable.id, onDelete = ReferenceOption.CASCADE)
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val addedAt = long("addedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(channelId, songId)
}

object RadioChannelArtistTable : Table("radioChannelArtist") {
    val channelId = reference("channelId", RadioChannelTable.id, onDelete = ReferenceOption.CASCADE)
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val addedAt = long("addedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(channelId, artistId)
}

object RadioChannelAlbumTable : Table("radioChannelAlbum") {
    val channelId = reference("channelId", RadioChannelTable.id, onDelete = ReferenceOption.CASCADE)
    val albumId = reference("albumId", AlbumTable.id, onDelete = ReferenceOption.CASCADE)
    val addedAt = long("addedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(channelId, albumId)
}
