package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.EntityType
import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.on
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HookServiceCoalescingTest {
    private class CountingSubscriber : EntityWriteSubscriber {
        var calls = 0
        var createdEntities = 0

        override fun created(type: EntityType, ids: Collection<UUID>) {
            calls++
            createdEntities += ids.size
        }

        override fun updated(type: EntityType, ids: Collection<UUID>, containersChanged: Boolean) {
            calls++
        }

        override fun relinked(type: EntityType, ids: Collection<UUID>) {
            calls++
        }

        override fun leavingContainers(type: EntityType, ids: Collection<UUID>) {
            calls++
        }

        override fun membersChanged(type: EntityType, ids: Collection<UUID>) {
            calls++
        }

        override fun deleting(type: EntityType, ids: Collection<UUID>) {
            calls++
        }

        override fun merging(type: EntityType, keptId: UUID, removedIds: Collection<UUID>) {
            calls++
        }

        override fun likesChanged(userId: UUID, type: EntityType, ids: Collection<UUID>) {
            calls++
        }

        override fun timecodesChanged(userId: UUID, songIds: Collection<UUID>) {
            calls++
        }
    }

    private lateinit var database: Database
    private val hooks = HookService()
    private val publisher = EntityEventPublisher(hooks)
    private val events = Channel<HookEvent>(Channel.UNLIMITED)
    private val collector: suspend (HookEvent) -> Unit = { events.send(it) }

    private fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "hook_service_coalescing_test")
        hooks.on<HookEvent.ListenIngested>(collector)
        hooks.on<HookEvent.PlaylistChanged>(collector)
        hooks.on<HookEvent.CollectionChanged>(collector)
        hooks.on<HookEvent.NowPlayingChanged>(collector)
        hooks.on<HookEvent.EntitiesCreated>(collector)
        hooks.on<HookEvent.EntitiesUpdated>(collector)
        hooks.on<HookEvent.EntityMembersChanged>(collector)
        hooks.on<HookEvent.EntitiesDeleted>(collector)
        hooks.on<HookEvent.EntitiesMerged>(collector)
        hooks.on<HookEvent.LikesChanged>(collector)
        hooks.on<HookEvent.TimecodesChanged>(collector)
        hooks.on<HookEvent.AlbumsLinkedToMusicBrainz>(collector)
        hooks.on<HookEvent.LibraryIndexed>(collector)
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    private suspend fun delivered(count: Int): List<HookEvent> {
        val received = List(count) { withTimeout(2.seconds) { events.receive() } }
        assertNull(withTimeoutOrNull(200.milliseconds) { events.receive() })
        return received
    }

    private fun ids(count: Int) = List(count) { UUID.randomUUID() }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `announcements of the same class and type become one event with the ids in first occurrence order`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val (a, b, c, d) = ids(4)
        val user = UUID.randomUUID()
        val otherUser = UUID.randomUUID()

        transaction(database) {
            publisher.created(EntityType.SONG, listOf(b, a))
            publisher.created(EntityType.ALBUM, listOf(d))
            publisher.created(EntityType.SONG, listOf(a, c, b))
            publisher.updated(EntityType.ARTIST, listOf(a))
            publisher.relinked(EntityType.ARTIST, listOf(b, a))
            publisher.updated(EntityType.ARTIST, listOf(c), containersChanged = true)
            publisher.membersChanged(EntityType.COLLECTION, listOf(a))
            publisher.membersChanged(EntityType.COLLECTION, listOf(a, b))
            publisher.deleting(EntityType.PLAYLIST, listOf(a))
            publisher.deleting(EntityType.PLAYLIST, listOf(b, a))
            publisher.merging(EntityType.USER_PLAYLIST, a, listOf(b))
            publisher.merging(EntityType.USER_PLAYLIST, a, listOf(c, b, a))
            publisher.merging(EntityType.USER_PLAYLIST, d, listOf(c))
            publisher.likesChanged(user, EntityType.SONG, listOf(a))
            publisher.likesChanged(user, EntityType.SONG, listOf(b, a))
            publisher.likesChanged(otherUser, EntityType.SONG, listOf(a))
            publisher.likesChanged(user, EntityType.ALBUM, listOf(d))
            publisher.timecodesChanged(user, listOf(a))
            publisher.timecodesChanged(user, listOf(a, b))
            publisher.timecodesChanged(otherUser, listOf(c))
            publisher.albumsLinkedToMusicBrainz(listOf(d))
            publisher.albumsLinkedToMusicBrainz(listOf(c, d))
            hooks.publish(HookEvent.LibraryIndexed)
            hooks.publish(HookEvent.LibraryIndexed)
        }

        val received = delivered(14)
        assertEquals(
            listOf<HookEvent>(
                HookEvent.EntitiesDeleted(EntityType.PLAYLIST, setOf(a, b)),
                HookEvent.EntitiesMerged(EntityType.USER_PLAYLIST, a, setOf(b, c)),
                HookEvent.EntitiesMerged(EntityType.USER_PLAYLIST, d, setOf(c)),
                HookEvent.EntitiesCreated(EntityType.SONG, setOf(b, a, c)),
                HookEvent.EntitiesCreated(EntityType.ALBUM, setOf(d)),
                HookEvent.EntitiesUpdated(EntityType.ARTIST, setOf(a, b, c)),
                HookEvent.EntityMembersChanged(EntityType.COLLECTION, setOf(a, b)),
                HookEvent.LikesChanged(user, EntityType.SONG, setOf(a, b)),
                HookEvent.LikesChanged(otherUser, EntityType.SONG, setOf(a)),
                HookEvent.LikesChanged(user, EntityType.ALBUM, setOf(d)),
                HookEvent.TimecodesChanged(user, setOf(a, b)),
                HookEvent.TimecodesChanged(otherUser, setOf(c)),
                HookEvent.AlbumsLinkedToMusicBrainz(setOf(d, c)),
                HookEvent.LibraryIndexed,
            ),
            received
        )
        assertEquals(listOf(b, a, c), (received[3] as HookEvent.EntitiesCreated).ids.toList())
        assertEquals(listOf(b, c), (received[1] as HookEvent.EntitiesMerged).removedIds.toList())
        assertEquals(listOf(d, c), (received[12] as HookEvent.AlbumsLinkedToMusicBrainz).albumIds.toList())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `events of one commit reach a handler in the fixed order of event classes`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (a, b, c, d) = ids(4)
        val user = UUID.randomUUID()

        transaction(database) {
            hooks.publish(HookEvent.CollectionChanged(a))
            hooks.publish(HookEvent.LibraryIndexed)
            publisher.albumsLinkedToMusicBrainz(listOf(a))
            publisher.timecodesChanged(user, listOf(a))
            publisher.likesChanged(user, EntityType.SONG, listOf(a))
            publisher.membersChanged(EntityType.ALBUM, listOf(a))
            publisher.updated(EntityType.SONG, listOf(a))
            publisher.created(EntityType.SONG, listOf(b))
            publisher.merging(EntityType.ARTIST, a, listOf(b))
            publisher.deleting(EntityType.SONG, listOf(c))
            hooks.publish(HookEvent.PlaylistChanged(d))
            hooks.publish(HookEvent.CollectionChanged(a))
        }

        assertEquals(
            listOf(
                HookEvent.EntitiesDeleted(EntityType.SONG, setOf(c)),
                HookEvent.EntitiesMerged(EntityType.ARTIST, a, setOf(b)),
                HookEvent.EntitiesCreated(EntityType.SONG, setOf(b)),
                HookEvent.EntitiesUpdated(EntityType.SONG, setOf(a)),
                HookEvent.EntityMembersChanged(EntityType.ALBUM, setOf(a)),
                HookEvent.LikesChanged(user, EntityType.SONG, setOf(a)),
                HookEvent.TimecodesChanged(user, setOf(a)),
                HookEvent.AlbumsLinkedToMusicBrainz(setOf(a)),
                HookEvent.LibraryIndexed,
                HookEvent.CollectionChanged(a),
                HookEvent.PlaylistChanged(d),
            ),
            delivered(11)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an entity created in the transaction is announced as created only`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (created, existing) = ids(2)

        transaction(database) {
            publisher.updated(EntityType.ALBUM, listOf(existing))
            publisher.created(EntityType.ALBUM, listOf(created))
            publisher.updated(EntityType.ALBUM, listOf(created, existing))
            publisher.relinked(EntityType.ALBUM, listOf(created))
            publisher.updated(EntityType.SONG, listOf(created))
            publisher.membersChanged(EntityType.ALBUM, listOf(created))
        }

        assertEquals(
            listOf(
                HookEvent.EntitiesCreated(EntityType.ALBUM, setOf(created)),
                HookEvent.EntitiesUpdated(EntityType.ALBUM, setOf(existing)),
                HookEvent.EntitiesUpdated(EntityType.SONG, setOf(created)),
                HookEvent.EntityMembersChanged(EntityType.ALBUM, setOf(created)),
            ),
            delivered(4)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an entity created and deleted in the same transaction is not announced at all`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val (transient, kept) = ids(2)

        transaction(database) {
            publisher.created(EntityType.SONG, listOf(transient, kept))
            publisher.updated(EntityType.SONG, listOf(transient))
            publisher.membersChanged(EntityType.SONG, listOf(transient))
            publisher.deleting(EntityType.SONG, listOf(transient))
            publisher.updated(EntityType.SONG, listOf(transient))
            publisher.membersChanged(EntityType.SONG, listOf(transient))
        }

        assertEquals(listOf(HookEvent.EntitiesCreated(EntityType.SONG, setOf(kept))), delivered(1))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a deleted entity is announced as deleted only`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (deleted, other) = ids(2)

        transaction(database) {
            publisher.updated(EntityType.SONG, listOf(deleted, other))
            publisher.membersChanged(EntityType.SONG, listOf(deleted))
            publisher.leavingContainers(EntityType.SONG, listOf(deleted))
            publisher.deleting(EntityType.SONG, listOf(deleted))
            publisher.updated(EntityType.SONG, listOf(deleted))
            publisher.membersChanged(EntityType.SONG, listOf(deleted))
            publisher.membersChanged(EntityType.ALBUM, listOf(deleted))
        }

        assertEquals(
            listOf(
                HookEvent.EntitiesDeleted(EntityType.SONG, setOf(deleted)),
                HookEvent.EntitiesUpdated(EntityType.SONG, setOf(other)),
                HookEvent.EntityMembersChanged(EntityType.ALBUM, setOf(deleted)),
            ),
            delivered(3)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an entity deleted and created again is announced as deleted and created`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (replaced, removedAgain) = ids(2)

        transaction(database) {
            publisher.deleting(EntityType.PLAYLIST, listOf(replaced, removedAgain))
            publisher.created(EntityType.PLAYLIST, listOf(replaced, removedAgain))
            publisher.updated(EntityType.PLAYLIST, listOf(replaced))
            publisher.membersChanged(EntityType.PLAYLIST, listOf(replaced, removedAgain))
            publisher.deleting(EntityType.PLAYLIST, listOf(removedAgain))
        }

        assertEquals(
            listOf(
                HookEvent.EntitiesDeleted(EntityType.PLAYLIST, setOf(replaced, removedAgain)),
                HookEvent.EntitiesCreated(EntityType.PLAYLIST, setOf(replaced)),
                HookEvent.EntityMembersChanged(EntityType.PLAYLIST, setOf(replaced)),
            ),
            delivered(3)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the entities a merge removes are announced by the merge only`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (kept, removed, createdAndRemoved) = ids(3)

        transaction(database) {
            publisher.created(EntityType.ARTIST, listOf(createdAndRemoved))
            publisher.updated(EntityType.ARTIST, listOf(removed, kept))
            publisher.membersChanged(EntityType.ARTIST, listOf(removed))
            publisher.merging(EntityType.ARTIST, kept, listOf(removed, createdAndRemoved))
            publisher.updated(EntityType.ARTIST, listOf(removed))
            publisher.membersChanged(EntityType.ARTIST, listOf(createdAndRemoved, kept))
        }

        assertEquals(
            listOf(
                HookEvent.EntitiesMerged(EntityType.ARTIST, kept, setOf(removed, createdAndRemoved)),
                HookEvent.EntitiesUpdated(EntityType.ARTIST, setOf(kept)),
                HookEvent.EntityMembersChanged(EntityType.ARTIST, setOf(kept)),
            ),
            delivered(3)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an entity a merge removes is not announced as deleted whichever came first`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val (kept, mergedThenDeleted, deletedThenMerged, onlyDeleted, createdAndRemoved) = ids(5)

            transaction(database) {
                publisher.created(EntityType.SONG, listOf(createdAndRemoved))
                publisher.deleting(EntityType.SONG, listOf(deletedThenMerged, onlyDeleted))
                publisher.merging(
                    EntityType.SONG,
                    kept,
                    listOf(mergedThenDeleted, deletedThenMerged, createdAndRemoved)
                )
                publisher.deleting(EntityType.SONG, listOf(mergedThenDeleted, createdAndRemoved))
                publisher.deleting(EntityType.ALBUM, listOf(mergedThenDeleted))
                publisher.updated(EntityType.SONG, listOf(mergedThenDeleted, deletedThenMerged, kept))
            }

            assertEquals(
                listOf(
                    HookEvent.EntitiesDeleted(EntityType.SONG, setOf(onlyDeleted)),
                    HookEvent.EntitiesDeleted(EntityType.ALBUM, setOf(mergedThenDeleted)),
                    HookEvent.EntitiesMerged(
                        EntityType.SONG,
                        kept,
                        setOf(mergedThenDeleted, deletedThenMerged, createdAndRemoved)
                    ),
                    HookEvent.EntitiesUpdated(EntityType.SONG, setOf(kept)),
                ),
                delivered(4)
            )
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a deletion of nothing but merged entities is not announced`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (kept, mergedThenDeleted, deletedThenMerged) = ids(3)

        transaction(database) {
            publisher.merging(EntityType.SONG, kept, listOf(mergedThenDeleted))
            publisher.deleting(EntityType.SONG, listOf(mergedThenDeleted))
        }
        assertEquals(listOf(HookEvent.EntitiesMerged(EntityType.SONG, kept, setOf(mergedThenDeleted))), delivered(1))

        transaction(database) {
            publisher.deleting(EntityType.SONG, listOf(deletedThenMerged))
            publisher.merging(EntityType.SONG, kept, listOf(deletedThenMerged))
        }
        assertEquals(listOf(HookEvent.EntitiesMerged(EntityType.SONG, kept, setOf(deletedThenMerged))), delivered(1))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `identical events of one transaction are sent once and announcements without ids are ignored`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val subscriber = CountingSubscriber()
        hooks.inTransaction(subscriber)
        val playlist = UUID.randomUUID()
        val user = UUID.randomUUID()

        transaction(database) {
            hooks.publish(HookEvent.PlaylistChanged(playlist))
            hooks.publish(HookEvent.PlaylistChanged(playlist))
            publisher.created(EntityType.SONG, emptyList())
            publisher.updated(EntityType.SONG, emptyList())
            publisher.relinked(EntityType.SONG, emptyList())
            publisher.leavingContainers(EntityType.SONG, emptyList())
            publisher.membersChanged(EntityType.SONG, emptyList())
            publisher.deleting(EntityType.SONG, emptyList())
            publisher.merging(EntityType.SONG, playlist, listOf(playlist))
            publisher.likesChanged(user, EntityType.SONG, emptyList())
            publisher.timecodesChanged(user, emptyList())
            publisher.albumsLinkedToMusicBrainz(emptyList())
        }

        assertEquals(listOf(HookEvent.PlaylistChanged(playlist)), delivered(1))
        assertEquals(0, subscriber.calls)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a failing handler affects neither the other handlers nor the publisher`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        hooks.on<HookEvent.EntitiesCreated> { throw IllegalStateException("boom") }
        val song = UUID.randomUUID()
        val album = UUID.randomUUID()

        transaction(database) {
            publisher.created(EntityType.SONG, listOf(song))
            publisher.updated(EntityType.ALBUM, listOf(album))
        }
        publisher.created(EntityType.ALBUM, listOf(album))

        assertEquals(
            setOf(
                HookEvent.EntitiesCreated(EntityType.SONG, setOf(song)),
                HookEvent.EntitiesUpdated(EntityType.ALBUM, setOf(album)),
                HookEvent.EntitiesCreated(EntityType.ALBUM, setOf(album)),
            ),
            delivered(3).toSet()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a slow handler does not block the publishing coroutine`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val release = CompletableDeferred<Unit>()
        val slowFinished = CompletableDeferred<Unit>()
        hooks.on<HookEvent.EntitiesCreated> {
            release.await()
            slowFinished.complete(Unit)
        }
        val song = UUID.randomUUID()

        transaction(database) { publisher.created(EntityType.SONG, listOf(song)) }
        publisher.created(EntityType.SONG, listOf(song))

        assertEquals(
            listOf(
                HookEvent.EntitiesCreated(EntityType.SONG, setOf(song)),
                HookEvent.EntitiesCreated(EntityType.SONG, setOf(song)),
            ),
            delivered(2)
        )
        assertEquals(false, slowFinished.isCompleted)
        release.complete(Unit)
        withTimeout(2.seconds) { slowFinished.await() }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a bulk announcement of 30000 ids in several steps is one event`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val subscriber = CountingSubscriber()
        hooks.inTransaction(subscriber)
        val songs = ids(30000)

        transaction(database) {
            publisher.created(EntityType.SONG, songs.subList(0, 12000))
            publisher.created(EntityType.SONG, songs.subList(8000, 21000))
            publisher.created(EntityType.SONG, songs.subList(21000, 30000) + songs.subList(0, 100))
            publisher.updated(EntityType.ALBUM, songs)
            publisher.deleting(EntityType.PLAYLIST, songs)
        }

        val received = delivered(3)
        assertEquals(HookEvent.EntitiesDeleted(EntityType.PLAYLIST, songs.toSet()), received[0])
        assertEquals(songs, (received[1] as HookEvent.EntitiesCreated).ids.toList())
        assertEquals(EntityType.SONG, (received[1] as HookEvent.EntitiesCreated).type)
        assertEquals(songs, (received[2] as HookEvent.EntitiesUpdated).ids.toList())
        assertEquals(5, subscriber.calls)
        assertEquals(12000 + 13000 + 9100, subscriber.createdEntities)
    }
}
