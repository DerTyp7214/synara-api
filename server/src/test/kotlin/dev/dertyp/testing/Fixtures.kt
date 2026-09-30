package dev.dertyp.testing

import dev.dertyp.db.*
import dev.dertyp.services.ScheduledTaskLogService
import io.mockk.coEvery
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

fun relaxedTaskLogService(): ScheduledTaskLogService {
    val logService = mockk<ScheduledTaskLogService>(relaxed = true)
    coEvery { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)
    return logService
}

fun insertUser(passwordHash: String = "hash"): UUID {
    val uid = UUID.randomUUID()
    UserTable.insert {
        it[id] = uid
        it[username] = "user_$uid"
        it[UserTable.passwordHash] = passwordHash
    }
    return uid
}

fun insertAlbum(name: String = "Album"): UUID {
    val aid = UUID.randomUUID()
    AlbumTable.insert {
        it[id] = aid
        it[AlbumTable.name] = name
    }
    return aid
}

fun insertArtist(name: String = "Artist"): UUID {
    val aid = UUID.randomUUID()
    ArtistTable.insert {
        it[id] = aid
        it[ArtistTable.name] = name
    }
    return aid
}

fun insertArtist(database: Database, id: UUID, name: String) {
    transaction(database) {
        ArtistTable.insert {
            it[ArtistTable.id] = id
            it[ArtistTable.name] = name
        }
    }
}

fun insertSong(albumId: UUID, title: String = "Song", durationMs: Long = 0, isrc: String? = null): UUID {
    val sid = UUID.randomUUID()
    SongTable.insert {
        it[id] = sid
        it[SongTable.title] = title
        it[SongTable.albumId] = albumId
        it[SongTable.isrc] = isrc
        it[fileSize] = 0
        it[duration] = durationMs
    }
    return sid
}

fun linkSongArtist(songId: UUID, artistId: UUID) {
    SongArtistTable.insert {
        it[SongArtistTable.songId] = songId
        it[SongArtistTable.artistId] = artistId
    }
}

fun insertLbUser(): UUID {
    val id = UUID.randomUUID()
    ListenBrainzUserTable.insert {
        it[ListenBrainzUserTable.id] = id
        it[username] = "lb_$id"
    }
    return id
}

fun linkListenBrainzUser(userId: UUID, lbUserId: UUID) {
    UserListenBrainzLinkTable.insert {
        it[UserListenBrainzLinkTable.userId] = userId
        it[listenBrainzUserId] = lbUserId
    }
}
