package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.data.ArtistAlias
import dev.dertyp.data.ArtistSplitAlias
import dev.dertyp.data.EntityType
import dev.dertyp.data.MergeArtists
import dev.dertyp.data.SplitArtist
import dev.dertyp.db.ArtistAliasTable
import dev.dertyp.db.ArtistMemberTable
import dev.dertyp.db.CollectionArtistTable
import dev.dertyp.db.CollectionTable
import dev.dertyp.db.MBArtistTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.testing.clearRecordedChanges
import dev.dertyp.testing.created
import dev.dertyp.testing.deleted
import dev.dertyp.testing.members
import dev.dertyp.testing.recordedChanges
import dev.dertyp.testing.recordedScopes
import dev.dertyp.testing.updated
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class ArtistEntityChangeTest : EntityChangeLibraryTest() {
    private fun collectionWith(member: UUID): UUID = db {
        val holder = CollectionTable.insertAndGetId {
            it[name] = "Collection"
            it[creator] = owner
        }.value
        CollectionArtistTable.insert {
            it[collectionId] = holder
            it[artistId] = member
        }
        holder
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `creating artists records only the new ones`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val existing = artist("Existing")

        val single = artistService.createArtist("Single").id
        assertEquals(setOf(created(EntityType.ARTIST, single)), recordedChanges(database))
        clearRecordedChanges(database)

        val resolved = artistService.getOrBulkCreate(listOf("Existing", "Fresh"))

        assertEquals(listOf(existing), resolved["Existing"])
        assertEquals(setOf(created(EntityType.ARTIST, resolved.getValue("Fresh").single())), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `aliases record the artist and a removed credited alias records the song`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val performer = artist("Performer")
        val song = song(album("Album"), "Title", performer)

        assertTrue(artistService.addAlias(performer, "Stage Name"))
        assertEquals(setOf(updated(EntityType.ARTIST, performer)), recordedChanges(database))
        clearRecordedChanges(database)

        assertFalse(artistService.addAlias(performer, "Stage Name"))
        assertFalse(artistService.removeAlias(performer, "Unknown"))
        assertEquals(emptySet<Any>(), recordedChanges(database))

        db {
            val alias = ArtistAliasTable.selectAll()
                .where { ArtistAliasTable.artistId eq performer }
                .andWhere { ArtistAliasTable.name eq "Stage Name" }
                .single()[ArtistAliasTable.id]
            SongArtistTable.update({ SongArtistTable.songId eq song }) { it[creditedAliasId] = alias }
        }

        assertTrue(artistService.removeAlias(performer, "Stage Name"))
        assertEquals(setOf(updated(EntityType.ARTIST, performer), updated(EntityType.SONG, song)), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `group members, the MusicBrainz link and mirrored artists are recorded once per real change`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val group = artist("Group")
        val member = artist("Member")
        val mbArtist = UUID.randomUUID()
        db {
            MBArtistTable.insert {
                it[id] = mbArtist
                it[name] = "Group"
                it[sortName] = "Group"
            }
        }

        artistService.setGroup(group, listOf(member))
        assertEquals(setOf(updated(EntityType.ARTIST, group)), recordedChanges(database))
        clearRecordedChanges(database)
        artistService.setGroup(group, listOf(member))
        assertEquals(emptySet<Any>(), recordedChanges(database))

        artistService.updateMusicBrainzLastCheck(member)
        assertEquals(emptySet<Any>(), recordedChanges(database))

        artistService.setMusicBrainzId(group, mbArtist)
        assertEquals(setOf(updated(EntityType.ARTIST, group)), recordedChanges(database))
        clearRecordedChanges(database)
        artistService.setMusicBrainzId(group, mbArtist)
        assertEquals(emptySet<Any>(), recordedChanges(database))

        val remote = artistService.byId(member)!!.copy(id = UUID.randomUUID(), name = "Mirrored")
        artistService.upsertArtist(remote)
        assertEquals(setOf(created(EntityType.ARTIST, remote.id)), recordedChanges(database))
        clearRecordedChanges(database)
        artistService.upsertArtist(remote)
        assertEquals(emptySet<Any>(), recordedChanges(database))

        artistService.upsertArtist(remote.copy(about = "Biography"))
        artistService.upsertArtistAlias(ArtistAlias(member, "Alias"))
        artistService.upsertArtistSplitAlias(ArtistSplitAlias(member, "Member & Friend"))
        assertEquals(setOf(updated(EntityType.ARTIST, remote.id), updated(EntityType.ARTIST, member)), recordedChanges(database))
        clearRecordedChanges(database)
        artistService.upsertArtistAlias(ArtistAlias(member, "Alias"))
        artistService.upsertArtistSplitAlias(ArtistSplitAlias(member, "Member & Friend"))
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting unreferenced artists records them and marks their collections`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val lone = artist("Lone")
        val credited = artist("Credited")
        song(album("Album"), "Title", credited)
        val collection = collectionWith(lone)

        assertEquals(1, artistService.deleteUnreferencedArtists())

        assertEquals(setOf(deleted(EntityType.ARTIST, lone), members(EntityType.COLLECTION, collection)), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `merging artists records the new artist, the removed ones and everything credited to them`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val first = artist("First")
        val second = artist("Second")
        val bystander = artist("Bystander")
        val songAlbum = album("Song Album")
        val song = song(songAlbum, "Title", first, bystander)
        val album = album("Credited Album", second)
        val collection = collectionWith(first)

        val merged = artistService.mergeArtists(MergeArtists(name = "Merged", artistIds = listOf(first, second)))!!.id

        assertEquals(
            setOf(
                created(EntityType.ARTIST, merged),
                members(EntityType.ARTIST, merged),
                deleted(EntityType.ARTIST, first),
                deleted(EntityType.ARTIST, second),
                updated(EntityType.SONG, song),
                updated(EntityType.ALBUM, album),
                members(EntityType.COLLECTION, collection),
            ),
            recordedChanges(database)
        )
        assertEquals(
            setOf(EntityType.ALBUM to songAlbum, EntityType.ARTIST to merged, EntityType.ARTIST to bystander),
            recordedScopes(database, EntityType.SONG, song)
        )
        assertEquals(setOf(EntityType.ARTIST to merged), recordedScopes(database, EntityType.ALBUM, album))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `splitting an artist records it as deleted and relinks its songs and albums`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val original = artist("Left & Right")
            val left = artist("Left")
            val right = artist("Right")
            val songAlbum = album("Song Album")
            val song = song(songAlbum, "Title", original)
            val album = album("Credited Album", original)

            artistService.splitArtist(SplitArtist(original, mapOf("Left" to left, "Right" to right)))

            assertEquals(
                setOf(
                    deleted(EntityType.ARTIST, original),
                    updated(EntityType.SONG, song),
                    updated(EntityType.ALBUM, album),
                    updated(EntityType.ARTIST, left),
                    updated(EntityType.ARTIST, right),
                    members(EntityType.ARTIST, left),
                    members(EntityType.ARTIST, right),
                    members(EntityType.ALBUM, songAlbum),
                ),
                recordedChanges(database)
            )
            assertEquals(
                setOf(EntityType.ALBUM to songAlbum, EntityType.ARTIST to left, EntityType.ARTIST to right),
                recordedScopes(database, EntityType.SONG, song)
            )
            assertEquals(
                setOf(EntityType.ARTIST to left, EntityType.ARTIST to right),
                recordedScopes(database, EntityType.ALBUM, album)
            )
        }

    private fun membersOf(group: UUID): List<UUID> = runBlocking { artistService.byId(group)!!.artists.map { it.id } }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `merging an artist that is a member records the group whose members a client reads`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val group = artist("Group")
        val otherGroup = artist("Other Group")
        val member = artist("Member")
        val duplicate = artist("Member Duplicate")
        val bystander = artist("Bystander")
        artistService.setGroup(group, listOf(member, bystander))
        artistService.setGroup(otherGroup, listOf(bystander))
        clearRecordedChanges(database)

        val merged = artistService.mergeArtists(MergeArtists(name = "Member", artistIds = listOf(member, duplicate)))!!.id

        assertEquals(setOf(merged, bystander), membersOf(group).toSet())
        assertEquals(listOf(bystander), membersOf(otherGroup))
        assertEquals(
            setOf(
                created(EntityType.ARTIST, merged),
                members(EntityType.ARTIST, merged),
                deleted(EntityType.ARTIST, member),
                deleted(EntityType.ARTIST, duplicate),
                updated(EntityType.ARTIST, group),
            ),
            recordedChanges(database)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `merging a group records the new group with its members and none of the members`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val group = artist("Group")
        val otherGroup = artist("Other Group")
        val solo = artist("Solo")
        val member = artist("Member")
        val shared = artist("Shared")
        artistService.setGroup(group, listOf(member, shared))
        artistService.setGroup(otherGroup, listOf(shared, solo))
        clearRecordedChanges(database)

        val merged = artistService.mergeArtists(
            MergeArtists(name = "Merged", artistIds = listOf(group, otherGroup, solo))
        )!!.id

        assertTrue(artistService.byId(merged)!!.isGroup)
        assertEquals(setOf(member, shared), membersOf(merged).toSet())
        assertEquals(2, membersOf(merged).size)
        assertEquals(
            setOf(
                created(EntityType.ARTIST, merged),
                members(EntityType.ARTIST, merged),
                deleted(EntityType.ARTIST, group),
                deleted(EntityType.ARTIST, otherGroup),
                deleted(EntityType.ARTIST, solo),
            ),
            recordedChanges(database)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `splitting an artist that is a member records the group that loses it`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val group = artist("Group")
        val otherGroup = artist("Other Group")
        val original = artist("Left & Right")
        val left = artist("Left")
        val right = artist("Right")
        val bystander = artist("Bystander")
        artistService.setGroup(group, listOf(original, bystander))
        artistService.setGroup(otherGroup, listOf(bystander))
        clearRecordedChanges(database)

        artistService.splitArtist(SplitArtist(original, mapOf("Left" to left, "Right" to right)))

        assertEquals(listOf(bystander), membersOf(group))
        assertEquals(listOf(bystander), membersOf(otherGroup))
        val recorded = recordedChanges(database)
        assertTrue(updated(EntityType.ARTIST, group) in recorded)
        assertTrue(deleted(EntityType.ARTIST, original) in recorded)
        assertFalse(updated(EntityType.ARTIST, otherGroup) in recorded)
        assertFalse(updated(EntityType.ARTIST, bystander) in recorded)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `joining or leaving a group records the group and not the member`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val group = artist("Group")
        val member = artist("Member")
        val recorder = getKoin().get<EntityChangeRecorder>()

        db {
            val groupBefore = entityStates(EntityType.ARTIST, listOf(group))
            val memberBefore = entityStates(EntityType.ARTIST, listOf(member))
            ArtistMemberTable.insert {
                it[groupId] = group
                it[artistId] = member
            }
            recorder.recordChanges(memberBefore)
            recorder.recordChanges(groupBefore)
        }
        assertEquals(setOf(updated(EntityType.ARTIST, group)), recordedChanges(database))
        clearRecordedChanges(database)

        db {
            val groupBefore = entityStates(EntityType.ARTIST, listOf(group))
            val memberBefore = entityStates(EntityType.ARTIST, listOf(member))
            ArtistMemberTable.deleteWhere { ArtistMemberTable.groupId eq group }
            recorder.recordChanges(memberBefore)
            recorder.recordChanges(groupBefore)
        }
        assertEquals(setOf(updated(EntityType.ARTIST, group)), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a split that fails leaves no record`(dialect: DbDialect) {
        setup(dialect)
        val original = artist("Left & Right")
        val song = song(album("Album"), "Title", original)

        assertThrows<Exception> {
            runBlocking { artistService.splitArtist(SplitArtist(original, mapOf("Missing" to UUID.randomUUID()))) }
        }

        assertEquals(emptySet<Any>(), recordedChanges(database))
        assertEquals(listOf(original), runBlocking { songService.byId(song)!!.artists.map { it.id } })
    }
}
