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


        fun mapPlaylist(resultRow: ResultRow): Playlist {
            val id = resultRow[PlaylistTable.id].value
            val name = resultRow[PlaylistTable.name]
            val imageId = resultRow[PlaylistTable.imageId]?.value

            return Playlist(
                id = id,
                name = name,
                songs = emptyList(),
                imageId = imageId,
            )
        }
    }

    fun map(resultRow: ResultRow): Playlist = mapPlaylist(resultRow)

    suspend fun byId(id: UUID): Playlist? = queryPlaylists {
        where { PlaylistTable.id eq id }
    }.singleOrNull()

    suspend fun byIdFull(id: UUID): Pair<String, List<PlaylistEntry>>? = dbQuery {
        val rows = PlaylistTable
            .leftJoin(
                PlaylistSongTable,
                onColumn = { PlaylistTable.id },
                otherColumn = { PlaylistSongTable.playlistId })
            .leftJoin(
                SongTable,
                onColumn = { PlaylistSongTable.songId },
                otherColumn = { SongTable.id }
            )
            .select(
                PlaylistTable.name,
                PlaylistSongTable.position,
                PlaylistSongTable.songId,
                SongTable.title,
                SongTable.duration
            )
            .where { PlaylistTable.id eq id }
            .toList()

        if (rows.isEmpty()) return@dbQuery null

        mapFullEagerly(rows)
    }

    suspend fun byName(name: String): Playlist? = queryPlaylists {
        where { PlaylistTable.name eq name }
    }.singleOrNull()

    suspend fun searchByName(name: String): List<Playlist> = queryPlaylists {
        where { PlaylistTable.name like "%$name%" }
    }

    suspend fun allPlaylists(): List<Playlist> = queryPlaylists()

    suspend fun delete(id: UUID): Boolean = dbQuery {
        PlaylistTable.deleteWhere() { PlaylistTable.id eq id } == 1
    }

    private suspend fun queryPlaylists(query: Query.() -> Query = { this }) = dbQuery {
        val mainPlaylistRows = PlaylistTable
            .selectAll()
            .query()
            .toList()

        if (mainPlaylistRows.isEmpty()) return@dbQuery listOf()

        val playlistIds = mainPlaylistRows.map { it[PlaylistTable.id].value }

        val songLinkRows = PlaylistSongTable
            .select(PlaylistSongTable.playlistId, PlaylistSongTable.songId, PlaylistSongTable.position)
            .where { PlaylistSongTable.playlistId inList playlistIds }
            .toList()

        val songIds = songLinkRows.map { it[PlaylistSongTable.songId].value }.distinct()

        val songDurationsById = if (songIds.isNotEmpty()) {
            getSongDurations(songIds)
        } else {
            emptyMap()
        }

        mapEagerly(mainPlaylistRows, songLinkRows, songDurationsById)
    }

    private suspend fun getSongDurations(songIds: List<UUID>): Map<UUID, Long> = dbQuery {
        SongTable
            .select(SongTable.id, SongTable.duration)
            .where { SongTable.id inList songIds }
            .associate { row ->
                row[SongTable.id].value to row[SongTable.duration]
            }
    }

    private fun mapEagerly(
        mainRows: List<ResultRow>,
        songLinkRows: List<ResultRow>,
        songDurationsById: Map<UUID, Long>
    ): List<Playlist> {
        val songsByPlaylistId = songLinkRows
            .map { row ->
                row[PlaylistSongTable.playlistId].value to
                        Pair(row[PlaylistSongTable.songId].value, row[PlaylistSongTable.position])
            }
            .groupBy({ it.first }, { it.second })

        return mainRows.map { playlistRow ->
            val playlist = map(playlistRow)
            val links = songsByPlaylistId[playlist.id] ?: listOf()

            val totalDuration = links
                .sumOf { (songId, _) ->
                    songDurationsById[songId] ?: 0L
                }.takeIf { it > 0L } ?: -1L

            val songs = songsByPlaylistId[playlist.id]
                ?.sortedBy { it.second }
                ?.map { it.first }
                ?: listOf()

            playlist.copy(
                songs = songs,
                totalDuration = totalDuration,
            )
        }
    }

    private fun mapFullEagerly(rows: List<ResultRow>): Pair<String, List<PlaylistEntry>> {
        val playlistName = rows.first()[PlaylistTable.name]

        val songEntriesWithPosition = rows
            .mapNotNull { row ->
                val songId = row.getOrNull(PlaylistSongTable.songId)?.value
                if (songId == null) {
                    return@mapNotNull null
                }

                Pair(
                    row[PlaylistSongTable.position],
                    PlaylistEntry(
                        id = songId,
                        name = row[SongTable.title],
                        duration = row[SongTable.duration]
                    )
                )
            }

        val sortedEntries = songEntriesWithPosition
            .sortedBy { it.first }
            .map { it.second }

        return Pair(playlistName, sortedEntries)
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