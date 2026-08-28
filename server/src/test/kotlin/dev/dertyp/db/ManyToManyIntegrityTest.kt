package dev.dertyp.db

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class ManyToManyIntegrityTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "many_to_many")
        transaction(database) {
            SchemaUtils.create(
                ArtistTable, SongTable, SongVariantTable, AlbumTable, SongArtistTable,
                PlaylistTable, PlaylistSongTable, UserTable, ImageTable
            )
        }
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `Song-Artist many-to-many relationship should work`(dialect: DbDialect) {
        setup(dialect)
        val songId = UUID.randomUUID()
        val artist1Id = UUID.randomUUID()
        val artist2Id = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            ArtistTable.insert { it[id] = artist1Id; it[name] = "Artist 1" }
            ArtistTable.insert { it[id] = artist2Id; it[name] = "Artist 2" }
            SongTable.insert { it[id] = songId; it[title] = "Song"; it[this.albumId] = albumId }
            
            SongArtistTable.insert { it[this.songId] = songId; it[this.artistId] = artist1Id }
            SongArtistTable.insert { it[this.songId] = songId; it[this.artistId] = artist2Id }
        }

        transaction(database) {
            val artistCount = SongArtistTable.selectAll().where { SongArtistTable.songId eq songId }.count()
            assertEquals(2, artistCount)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `Playlist-Song many-to-many relationship should work`(dialect: DbDialect) {
        setup(dialect)
        val playlistId = UUID.randomUUID()
        val song1Id = UUID.randomUUID()
        val song2Id = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = song1Id; it[title] = "Song 1"; it[this.albumId] = albumId }
            SongTable.insert { it[id] = song2Id; it[title] = "Song 2"; it[this.albumId] = albumId }
            PlaylistTable.insert { it[id] = playlistId; it[name] = "Playlist" }
            
            PlaylistSongTable.insert { it[this.playlistId] = playlistId; it[this.songId] = song1Id; it[position] = 1 }
            PlaylistSongTable.insert { it[this.playlistId] = playlistId; it[this.songId] = song2Id; it[position] = 2 }
        }

        transaction(database) {
            val songCount = PlaylistSongTable.selectAll().where { PlaylistSongTable.playlistId eq playlistId }.count()
            assertEquals(2, songCount)
        }
    }
}
