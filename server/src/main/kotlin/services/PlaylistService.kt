package dev.dertyp.services

import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.Playlist
import dev.dertyp.data.PlaylistEntry
import dev.dertyp.db.PlaylistSongTable
import dev.dertyp.db.PlaylistTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class PlaylistService(database: Database) {
    init {
        transaction(database) {
            SchemaUtils.create(PlaylistTable)
            SchemaUtils.create(PlaylistSongTable)
        }

        instance = this
    }

    companion object {
        var instance: PlaylistService? = null
            private set


        suspend fun mapPlaylist(resultRow: ResultRow, fetchSongs: Boolean = true): Playlist {
            val id = resultRow[PlaylistTable.id].value
            val name = resultRow[PlaylistTable.name]
            val imageId = resultRow[PlaylistTable.imageId]?.value

            val songs = mutableListOf<UUID>()

            if (fetchSongs) {
                songs.addAll(
                    dbQuery {
                        PlaylistSongTable
                            .select(PlaylistSongTable.songId, PlaylistSongTable.position)
                            .where { PlaylistSongTable.playlistId eq id }
                            .map { Pair(it[PlaylistSongTable.songId].value, it[PlaylistSongTable.position]) }
                            .sortedBy { it.second }
                            .map { it.first }
                    }
                )
            }

            return Playlist(
                id = id,
                name = name,
                songs = songs,
                imageId = imageId,
            )
        }
    }

    suspend fun map(resultRow: ResultRow, fetchSongs: Boolean = true): Playlist = mapPlaylist(resultRow, fetchSongs)

    suspend fun byId(id: UUID): Playlist? = dbQuery {
        PlaylistTable
            .selectAll()
            .where { PlaylistTable.id eq id }
            .map { map(it) }.singleOrNull()
    }

    suspend fun byIdFull(id: UUID): Pair<String, List<PlaylistEntry>>? = dbQuery {
        PlaylistTable
            .selectAll()
            .where { PlaylistTable.id eq id }
            .map { resultRow ->
                val id = resultRow[PlaylistTable.id].value
                val name = resultRow[PlaylistTable.name]
                val imageId = resultRow[PlaylistTable.imageId]?.value

                val songs = mutableListOf<PlaylistEntry>()

                songs.addAll(
                    dbQuery {
                        PlaylistSongTable
                            .join(
                                SongTable,
                                JoinType.INNER,
                                additionalConstraint = { SongTable.id eq PlaylistSongTable.songId }
                            )
                            .select(
                                PlaylistSongTable.position,
                                PlaylistSongTable.songId,
                                SongTable.title,
                                SongTable.duration
                            )
                            .where { PlaylistSongTable.playlistId eq id }
                            .map {
                                Pair(
                                    it[PlaylistSongTable.position], PlaylistEntry(
                                        id = it[PlaylistSongTable.songId].value,
                                        name = it[SongTable.title],
                                        duration = it[SongTable.duration]
                                    )
                                )
                            }
                            .sortedBy { it.first }
                            .map { it.second }
                    }
                )

                Pair(name, songs)
            }.singleOrNull()
    }

    suspend fun byName(name: String): Playlist? = dbQuery {
        PlaylistTable
            .selectAll()
            .where { PlaylistTable.name eq name }
            .map { map(it) }
            .singleOrNull()
    }

    suspend fun searchByName(name: String): List<Playlist> = dbQuery {
        PlaylistTable
            .selectAll()
            .where { PlaylistTable.name like "%$name%" }
            .map { map(it, false) }
    }

    suspend fun delete(id: UUID): Boolean = dbQuery {
        PlaylistTable.deleteWhere() { PlaylistTable.id eq id } == 1
    }

    suspend fun getOrCreate(insertablePlaylist: InsertablePlaylist): UUID? {
        val playlist = byName(insertablePlaylist.name)
        if (playlist != null) return playlist.id

        val imageId = insertablePlaylist.imageHash?.let { ImageService.instance?.byHash(it)?.id }

        val songs = dbQuery {
            SongTable
                .select(SongTable.id)
                .where { SongTable.filePath inList insertablePlaylist.songPaths }
                .map { it[SongTable.id].value }
        }

        if (songs.isEmpty()) return null

        val playlistId = dbQuery {
            PlaylistTable.insertAndGetId {
                it[PlaylistTable.name] = insertablePlaylist.name
                it[PlaylistTable.imageId] = imageId
            }
        }.value

        dbQuery {
            var position = 1
            PlaylistSongTable.batchInsert(songs) {
                this[PlaylistSongTable.songId] = it
                this[PlaylistSongTable.playlistId] = playlistId
                this[PlaylistSongTable.position] = position++
            }
        }

        return playlistId
    }
}