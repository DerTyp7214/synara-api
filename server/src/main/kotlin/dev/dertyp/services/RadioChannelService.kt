package dev.dertyp.services

import dev.dertyp.data.InsertableRadioChannel
import dev.dertyp.data.RadioChannel
import dev.dertyp.data.RadioChannelItemType
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.Random
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import java.util.*

class RadioChannelService : Service() {
    private val imageService by inject<ImageService>()

    private fun map(row: ResultRow) = RadioChannel(
        id = row[RadioChannelTable.id].value,
        name = row[RadioChannelTable.name],
        description = row[RadioChannelTable.description],
        imageId = row[RadioChannelTable.imageId]?.value,
        blurHash = row.getOrNull(ImageTable.blurHash),
        enabled = row[RadioChannelTable.enabled],
        position = row[RadioChannelTable.position],
        discovery = row[RadioChannelTable.discovery],
    )

    suspend fun list(includeDisabled: Boolean): List<RadioChannel> = dbQuery {
        RadioChannelTable
            .leftJoin(ImageTable, onColumn = { RadioChannelTable.imageId }, otherColumn = { ImageTable.id })
            .selectAll()
            .apply { if (!includeDisabled) andWhere { RadioChannelTable.enabled eq true } }
            .orderBy(RadioChannelTable.position to SortOrder.ASC, RadioChannelTable.name to SortOrder.ASC)
            .map { map(it) }
            .map { it.withCounts() }
    }

    suspend fun byId(id: UUID): RadioChannel? = dbQuery {
        RadioChannelTable
            .leftJoin(ImageTable, onColumn = { RadioChannelTable.imageId }, otherColumn = { ImageTable.id })
            .selectAll()
            .where { RadioChannelTable.id eq id }
            .firstOrNull()
            ?.let { map(it) }
    }?.withCounts()

    suspend fun create(channel: InsertableRadioChannel, creatorId: UUID): UUID = dbQuery {
        RadioChannelTable.insertAndGetId {
            it[name] = channel.name
            it[description] = channel.description
            it[enabled] = channel.enabled
            it[position] = channel.position
            it[discovery] = channel.discovery
            it[createdBy] = EntityID(creatorId, UserTable)
        }.value
    }

    suspend fun update(id: UUID, channel: InsertableRadioChannel): Boolean = dbQuery {
        RadioChannelTable.update({ RadioChannelTable.id eq id }) {
            it[name] = channel.name
            it[description] = channel.description
            it[enabled] = channel.enabled
            it[position] = channel.position
            it[discovery] = channel.discovery
        } == 1
    }

    suspend fun delete(id: UUID): Boolean = dbQuery {
        RadioChannelTable.deleteWhere { RadioChannelTable.id eq id } == 1
    }

    suspend fun setImage(id: UUID, bytes: ByteArray) {
        val imageId = imageService.createImage(bytes, "radio_channel")
        dbQuery {
            RadioChannelTable.update({ RadioChannelTable.id eq id }) {
                it[RadioChannelTable.imageId] = imageId
            }
        }
    }

    suspend fun addItem(id: UUID, type: RadioChannelItemType, itemId: UUID): Boolean = dbQuery {
        when (type) {
            RadioChannelItemType.SONG -> {
                if (SongTable.select(SongTable.id).where { SongTable.id eq itemId }.empty()) return@dbQuery false
                RadioChannelSongTable.insertIgnore { it[channelId] = id; it[songId] = itemId }.insertedCount > 0
            }
            RadioChannelItemType.ARTIST -> {
                if (ArtistTable.select(ArtistTable.id).where { ArtistTable.id eq itemId }.empty()) return@dbQuery false
                RadioChannelArtistTable.insertIgnore { it[channelId] = id; it[artistId] = itemId }.insertedCount > 0
            }
            RadioChannelItemType.ALBUM -> {
                if (AlbumTable.select(AlbumTable.id).where { AlbumTable.id eq itemId }.empty()) return@dbQuery false
                RadioChannelAlbumTable.insertIgnore { it[channelId] = id; it[albumId] = itemId }.insertedCount > 0
            }
        }
    }

    suspend fun removeItem(id: UUID, type: RadioChannelItemType, itemId: UUID): Boolean = dbQuery {
        when (type) {
            RadioChannelItemType.SONG ->
                RadioChannelSongTable.deleteWhere { (channelId eq id) and (songId eq itemId) } > 0
            RadioChannelItemType.ARTIST ->
                RadioChannelArtistTable.deleteWhere { (channelId eq id) and (artistId eq itemId) } > 0
            RadioChannelItemType.ALBUM ->
                RadioChannelAlbumTable.deleteWhere { (channelId eq id) and (albumId eq itemId) } > 0
        }
    }

    suspend fun randomSongs(id: UUID, exclude: Set<UUID>, limit: Int): List<UUID> = dbQuery {
        val channelSongs = RadioChannelSongTable.select(RadioChannelSongTable.songId)
            .where { RadioChannelSongTable.channelId eq id }
        val channelAlbums = RadioChannelAlbumTable.select(RadioChannelAlbumTable.albumId)
            .where { RadioChannelAlbumTable.channelId eq id }
        val channelArtists = RadioChannelArtistTable.select(RadioChannelArtistTable.artistId)
            .where { RadioChannelArtistTable.channelId eq id }

        val membership = (SongTable.id inSubQuery channelSongs) or
            (SongTable.albumId inSubQuery channelAlbums) or
            (SongTable.id inSubQuery SongArtistTable.select(SongArtistTable.songId)
                .where { SongArtistTable.artistId inSubQuery channelArtists }) or
            (SongTable.albumId inSubQuery AlbumArtistTable.select(AlbumArtistTable.albumId)
                .where { AlbumArtistTable.artistId inSubQuery channelArtists })

        var query = SongTable.select(SongTable.id).where { membership }
        if (exclude.isNotEmpty()) query = query.andWhere { SongTable.id notInList exclude }
        query.orderBy(Random()).limit(limit).map { it[SongTable.id].value }
    }

    private suspend fun RadioChannel.withCounts(): RadioChannel = dbQuery {
        copy(
            songCount = RadioChannelSongTable.selectAll().where { RadioChannelSongTable.channelId eq id }.count().toInt(),
            artistCount = RadioChannelArtistTable.selectAll().where { RadioChannelArtistTable.channelId eq id }.count().toInt(),
            albumCount = RadioChannelAlbumTable.selectAll().where { RadioChannelAlbumTable.channelId eq id }.count().toInt(),
        )
    }
}
