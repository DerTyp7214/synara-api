package dev.dertyp.core.wire.fixtures

import dev.dertyp.TestDatabase
import dev.dertyp.DbDialect
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.SongTable
import dev.dertyp.services.LegacyLyricsCache
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID

class LegacyLyricsFixture : AutoCloseable {
    val database: Database = TestDatabase.connect(DbDialect.SQLITE, "legacy_lyrics", AlbumTable, ArtistTable, SongTable)
    val cache = LegacyLyricsCache()
    private val albumId = UUID.randomUUID()

    init {
        startKoin { modules(module { single { cache } }) }
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
        }
    }

    fun insertSong(lyrics: String, id: UUID = UUID.randomUUID()): UUID = transaction(database) {
        SongTable.insert {
            it[SongTable.id] = id
            it[title] = "Song"
            it[SongTable.albumId] = this@LegacyLyricsFixture.albumId
            it[SongTable.lyrics] = lyrics
        }
        id
    }

    fun setLyrics(id: UUID, lyrics: String) {
        transaction(database) {
            SongTable.update({ SongTable.id eq id }) { it[SongTable.lyrics] = lyrics }
        }
    }

    override fun close() {
        stopKoin()
        TestDatabase.cleanUp()
    }
}
