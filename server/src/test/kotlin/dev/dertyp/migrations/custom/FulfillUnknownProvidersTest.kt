package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID
import kotlin.test.assertEquals

class FulfillUnknownProvidersTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "fulfill_unknown_providers_test")
        transaction(database) {
            SchemaUtils.create(
                ArtistTable,
                AlbumTable,
                SongTable,
                MBReleaseGroupTable,
                SongProviderTable,
                AlbumProviderTable,
                RecentReleaseTable,
                RecentReleaseProviderTable
            )
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `migration should fulfill unknown providers`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[this.albumId] = albumId
            }
            MBReleaseGroupTable.insert {
                it[id] = releaseId
                it[title] = "Release"
            }

            SongProviderTable.insert {
                it[this.songId] = songId
                it[provider] = "unknown"
                it[externalId] = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
                it[rawUrl] = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            }
            AlbumProviderTable.insert {
                it[this.albumId] = albumId
                it[provider] = "unknown"
                it[externalId] = "https://thekali.bandcamp.com/album/nirvana"
                it[rawUrl] = "https://thekali.bandcamp.com/album/nirvana"
            }
            RecentReleaseProviderTable.insert {
                it[this.releaseId] = releaseId
                it[provider] = "unknown"
                it[externalId] = "https://www.allmusic.com/album/mw0003563247"
                it[rawUrl] = "https://www.allmusic.com/album/mw0003563247"
            }
        }

        val migration = FulfillUnknownProviders()
        migration.migrate()

        transaction(database) {
            val songProviders = SongProviderTable.selectAll().where { SongProviderTable.songId eq songId }.toList()
            assertEquals(1, songProviders.size)
            assertEquals("youtube", songProviders[0][SongProviderTable.provider])
            assertEquals("dQw4w9WgXcQ", songProviders[0][SongProviderTable.externalId])

            val albumProviders = AlbumProviderTable.selectAll().where { AlbumProviderTable.albumId eq albumId }.toList()
            assertEquals(1, albumProviders.size)
            assertEquals("bandcamp", albumProviders[0][AlbumProviderTable.provider])
            assertEquals("thekali/album/nirvana", albumProviders[0][AlbumProviderTable.externalId])

            val releaseProviders = RecentReleaseProviderTable.selectAll().where { RecentReleaseProviderTable.releaseId eq releaseId }.toList()
            assertEquals(1, releaseProviders.size)
            assertEquals("allmusic", releaseProviders[0][RecentReleaseProviderTable.provider])
            assertEquals("mw0003563247", releaseProviders[0][RecentReleaseProviderTable.externalId])
        }
    }
}
