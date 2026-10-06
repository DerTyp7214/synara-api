package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.data.EntityType
import dev.dertyp.data.LikeLevel
import dev.dertyp.data.MergeArtists
import dev.dertyp.data.TimecodeTagInput
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.db.ArtistMusicBrainzTable
import dev.dertyp.db.MBArtistTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserTable
import dev.dertyp.testing.clearRecordedChanges
import dev.dertyp.testing.liked
import dev.dertyp.testing.recordedChanges
import dev.dertyp.testing.recordedUserChanges
import dev.dertyp.testing.timecodes
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.util.UUID

class UserStateEntityChangeTest : EntityChangeContentTest() {
    private fun nothing() {
        assertEquals(emptySet<Any>(), recordedUserChanges(database))
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    private fun only(vararg expected: Any) {
        assertEquals(expected.toSet(), recordedUserChanges(database))
        assertEquals(emptySet<Any>(), recordedChanges(database))
        clearRecordedChanges(database)
    }

    private fun linkedArtist(artistName: String): Pair<UUID, UUID> {
        val artist = artist(artistName)
        val musicBrainzArtist = UUID.randomUUID()
        db {
            MBArtistTable.insert {
                it[id] = musicBrainzArtist
                it[name] = artistName
                it[sortName] = artistName
            }
            ArtistMusicBrainzTable.insert {
                it[artistId] = artist
                it[musicBrainzId] = musicBrainzArtist
            }
        }
        return artist to musicBrainzArtist
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `liking, changing the level of and unliking a song records the like of that user`(dialect: DbDialect) =
        runBlocking {
            setupContent(dialect)
            val album = album("Album")
            val song = song(album, "Song")
            val other = song(album, "Other", track = 2)

            songService.setLikeLevelReturning(song, stranger, LikeLevel.LIKE)
            only(liked(stranger, EntityType.SONG, song))

            assertEquals(LikeLevel.LIKE, songService.setLikeLevelReturning(song, owner, LikeLevel.LIKE)!!.likeLevel)
            only(liked(owner, EntityType.SONG, song))
            songService.setLikeLevelReturning(song, owner, LikeLevel.LIKE)
            nothing()

            songService.setLikeLevelReturning(song, owner, LikeLevel.SUPER)
            only(liked(owner, EntityType.SONG, song))
            songService.setLikeLevelReturning(song, owner, LikeLevel.SUPER)
            nothing()

            songService.setLikeLevelReturning(song, owner, LikeLevel.NONE)
            only(liked(owner, EntityType.SONG, song))
            songService.setLikeLevelReturning(song, owner, LikeLevel.NONE)
            nothing()

            val likedAt = Instant.ofEpochMilli(5_000)
            songService.setLiked(other, owner, true, likedAt)
            only(liked(owner, EntityType.SONG, other))
            songService.setLiked(other, owner, true, likedAt)
            nothing()
            songService.setLiked(other, owner, true, Instant.ofEpochMilli(6_000))
            only(liked(owner, EntityType.SONG, other))
            songService.setLiked(other, owner, false, likedAt)
            only(liked(owner, EntityType.SONG, other))

            assertEquals(LikeLevel.LIKE, songService.byId(song, stranger)!!.likeLevel)
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `starring and unstarring an album records the star of that user`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val album = album("Album")
        subsonicQueryService.setAlbumStar(stranger, album, true)
        only(liked(stranger, EntityType.ALBUM, album))

        subsonicQueryService.setAlbumStar(owner, album, false)
        nothing()
        subsonicQueryService.setAlbumStar(owner, album, true)
        only(liked(owner, EntityType.ALBUM, album))
        subsonicQueryService.setAlbumStar(owner, album, true)
        nothing()
        subsonicQueryService.setAlbumStar(owner, album, false)
        only(liked(owner, EntityType.ALBUM, album))
        subsonicQueryService.setAlbumStar(owner, album, false)
        nothing()

        assertEquals(setOf(album), subsonicQueryService.starredAlbumStars(stranger).keys)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `following and unfollowing an artist records the follow of that user`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val (artist, musicBrainzArtist) = linkedArtist("Followed")

        releaseService.followArtist(stranger, musicBrainzArtist)
        only(liked(stranger, EntityType.ARTIST, artist))

        releaseService.followArtist(owner, musicBrainzArtist)
        only(liked(owner, EntityType.ARTIST, artist))
        releaseService.followArtist(owner, musicBrainzArtist)
        nothing()

        assertTrue(releaseService.unfollowArtist(owner, artist))
        only(liked(owner, EntityType.ARTIST, artist))
        assertFalse(releaseService.unfollowArtist(owner, artist))
        nothing()

        subsonicQueryService.setArtistStar(owner, artist, true)
        only(liked(owner, EntityType.ARTIST, artist))
        subsonicQueryService.setArtistStar(owner, artist, true)
        nothing()

        assertTrue(releaseService.unfollowArtistByMusicBrainzId(owner, musicBrainzArtist))
        only(liked(owner, EntityType.ARTIST, artist))
        assertFalse(releaseService.unfollowArtistByMusicBrainzId(owner, musicBrainzArtist))
        subsonicQueryService.setArtistStar(owner, artist, false)
        nothing()

        subsonicQueryService.setArtistStar(owner, artist, true)
        clearRecordedChanges(database)
        subsonicQueryService.setArtistStar(owner, artist, false)
        only(liked(owner, EntityType.ARTIST, artist))

        assertEquals(listOf(artist), subsonicQueryService.starredArtistIds(stranger))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `creating, editing, replacing and deleting timecode tags records the song for that user`(
        dialect: DbDialect
    ) = runBlocking {
        setupContent(dialect)
        val album = album("Album")
        val song = song(album, "Song")

        val tag = timecodeTagService.createTag(owner, song, TimecodeTagType.NOTE, "Note", 1_000, null)
        only(timecodes(owner, song))

        timecodeTagService.updateTag(owner, tag.id, TimecodeTagType.NOTE, "Edited", 2_000, null)
        only(timecodes(owner, song))

        assertFalse(timecodeTagService.deleteTag(stranger, tag.id))
        assertEquals(emptyList<Any>(), timecodeTagService.replaceTags(stranger, song, emptyList()))
        nothing()

        assertTrue(timecodeTagService.deleteTag(owner, tag.id))
        only(timecodes(owner, song))
        assertFalse(timecodeTagService.deleteTag(owner, tag.id))
        nothing()

        timecodeTagService.replaceTags(owner, song, listOf(TimecodeTagInput(TimecodeTagType.NOTE, "Replaced", 3_000)))
        only(timecodes(owner, song))
        timecodeTagService.replaceTags(owner, song, emptyList())
        only(timecodes(owner, song))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a song merge records the like that moves to the kept song`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val album = album("Album")
        val kept = song(album, "Title", location = "/music/same.flac")
        val removed = song(album, "Title", location = "/music/same.flac")
        db {
            SongTable.update({ SongTable.id eq kept }) { it[inserted] = 1000 }
            SongTable.update({ SongTable.id eq removed }) { it[inserted] = 2000 }
        }
        val bystander = db {
            UserTable.insert {
                it[username] = "bystander"
                it[passwordHash] = "hash"
            }[UserTable.id].value
        }
        songService.setLikeLevelReturning(removed, owner, LikeLevel.LIKE)
        songService.setLikeLevelReturning(removed, stranger, LikeLevel.SUPER)
        songService.setLikeLevelReturning(kept, stranger, LikeLevel.LIKE)
        songService.setLikeLevelReturning(removed, bystander, LikeLevel.LIKE)
        songService.setLikeLevelReturning(kept, bystander, LikeLevel.LIKE)
        clearRecordedChanges(database)

        val result = libraryMergeService.mergeDuplicates()

        assertEquals(1, result["songsMerged"])
        assertEquals(
            setOf(liked(owner, EntityType.SONG, kept), liked(stranger, EntityType.SONG, kept)),
            recordedUserChanges(database)
        )
        assertEquals(LikeLevel.LIKE, songService.byId(kept, owner)!!.likeLevel)
        assertEquals(LikeLevel.SUPER, songService.byId(kept, stranger)!!.likeLevel)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an artist merge records the follow that moves to the merged artist`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val first = artist("First")
        val second = artist("Second")
        subsonicQueryService.setArtistStar(owner, first, true)
        subsonicQueryService.setArtistStar(owner, second, true)
        clearRecordedChanges(database)

        val merged = artistService.mergeArtists(MergeArtists(name = "Merged", artistIds = listOf(first, second)))!!.id

        assertEquals(setOf(liked(owner, EntityType.ARTIST, merged)), recordedUserChanges(database))
        assertEquals(listOf(merged), subsonicQueryService.starredArtistIds(owner))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `changing the profile of a user records nothing`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)

        userService.updateDisplayName(owner, "Displayed")
        userService.updateProfileImage(owner, picture())

        assertEquals("Displayed", userService.findUserById(owner)!!.displayName)
        nothing()
    }
}
