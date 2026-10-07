package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.EntityType
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.HookGroup
import dev.dertyp.plugins.hookGroup
import dev.dertyp.plugins.on
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PluginHooksTest {
    private object PluginProbeTable : Table("plugin_hook_probe") {
        val marker = javaUUID("marker")
    }

    private val hooks = HookService()
    private val publisher = EntityEventPublisher(hooks)

    @AfterEach
    fun tearDown() {
        runBlocking { hooks.stopService() }
        TestDatabase.cleanUp()
    }

    private inline fun <reified E : HookEvent> HookBus.collect(): Channel<E> {
        val events = Channel<E>(Channel.UNLIMITED)
        on<E> { events.send(it) }
        return events
    }

    private suspend fun <T> Channel<T>.next(): T = withTimeout(2.seconds) { receive() }

    private suspend fun <T> Channel<T>.assertNothingMore() =
        assertNull(withTimeoutOrNull(200.milliseconds) { receive() })

    @Test
    fun `entity events are delivered only for the types of a declared group`() = runBlocking {
        val library = hooks.forPlugin("library", setOf(HookGroup.LIBRARY))
        val playlists = hooks.forPlugin("playlists", setOf(HookGroup.PLAYLISTS))
        val libraryEvents = library.collect<HookEvent.EntitiesCreated>()
        val playlistEvents = playlists.collect<HookEvent.EntitiesCreated>()
        val song = HookEvent.EntitiesCreated(EntityType.SONG, setOf(UUID.randomUUID()))
        val playlist = HookEvent.EntitiesCreated(EntityType.USER_PLAYLIST, setOf(UUID.randomUUID()))
        val collection = HookEvent.EntitiesCreated(EntityType.COLLECTION, setOf(UUID.randomUUID()))

        hooks.publish(song)
        hooks.publish(playlist)
        hooks.publish(collection)

        assertEquals(song, libraryEvents.next())
        libraryEvents.assertNothingMore()
        assertEquals(setOf(playlist, collection), setOf(playlistEvents.next(), playlistEvents.next()))
        playlistEvents.assertNothingMore()
    }

    @Test
    fun `subscribing outside the declared groups is rejected and names the missing group`() {
        val library = hooks.forPlugin("library", setOf(HookGroup.LIBRARY))
        val nothing = hooks.forPlugin("nothing", emptySet())

        val likes = assertThrows<IllegalArgumentException> { library.on<HookEvent.LikesChanged> { } }
        val timecodes = assertThrows<IllegalArgumentException> { library.on<HookEvent.TimecodesChanged> { } }
        val nowPlaying = assertThrows<IllegalArgumentException> { library.on<HookEvent.NowPlayingChanged> { } }
        val listens = assertThrows<IllegalArgumentException> { library.on<HookEvent.ListenIngested> { } }
        val playlistChanged = assertThrows<IllegalArgumentException> { library.on<HookEvent.PlaylistChanged> { } }
        val created = assertThrows<IllegalArgumentException> { nothing.on<HookEvent.EntitiesCreated> { } }
        val anything = assertThrows<IllegalArgumentException> { library.on(HookEvent::class) { } }

        assertEquals("Plugin library subscribes to LikesChanged without declaring hook group USER_STATE", likes.message)
        assertTrue(timecodes.message!!.endsWith(HookGroup.USER_STATE.name))
        assertTrue(nowPlaying.message!!.endsWith(HookGroup.PLAYBACK.name))
        assertTrue(listens.message!!.endsWith(HookGroup.PLAYBACK.name))
        assertTrue(playlistChanged.message!!.endsWith(HookGroup.PLAYLISTS.name))
        assertEquals(
            "Plugin nothing subscribes to EntitiesCreated without declaring hook group LIBRARY or PLAYLISTS",
            created.message
        )
        assertEquals("Plugin library subscribes to HookEvent, which is not a hook event", anything.message)
        assertEquals(emptyList<Any>(), hooks.pluginRegistrations("library"))
        assertEquals(emptyList<Any>(), hooks.pluginRegistrations("nothing"))
    }

    @Test
    fun `an event without a hook group cannot be subscribed to and reaches no plugin`() = runBlocking {
        val plugin = hooks.forPlugin("all", HookGroup.entries.toSet())
        val internal = Channel<HookEvent.AlbumsLinkedToMusicBrainz>(Channel.UNLIMITED)
        hooks.on<HookEvent.AlbumsLinkedToMusicBrainz> { internal.send(it) }
        val updates = plugin.collect<HookEvent.EntitiesUpdated>()
        val album = UUID.randomUUID()

        val rejected = assertThrows<IllegalArgumentException> { plugin.on<HookEvent.AlbumsLinkedToMusicBrainz> { } }
        publisher.albumsLinkedToMusicBrainz(listOf(album))
        publisher.updated(EntityType.ALBUM, listOf(album))

        assertEquals(
            "Plugin all subscribes to AlbumsLinkedToMusicBrainz, which is not available to plugins",
            rejected.message
        )
        assertNull(HookEvent.AlbumsLinkedToMusicBrainz(setOf(album)).hookGroup())
        assertEquals(HookEvent.AlbumsLinkedToMusicBrainz(setOf(album)), internal.next())
        assertEquals(HookEvent.EntitiesUpdated(EntityType.ALBUM, setOf(album)), updates.next())
        assertEquals(listOf(HookEvent.EntitiesUpdated::class), hooks.pluginRegistrations("all"))
    }

    @Test
    fun `per user events reach a plugin that declares their group`() = runBlocking {
        val plugin = hooks.forPlugin("user", setOf(HookGroup.USER_STATE, HookGroup.PLAYBACK))
        val likes = plugin.collect<HookEvent.LikesChanged>()
        val timecodes = plugin.collect<HookEvent.TimecodesChanged>()
        val nowPlaying = plugin.collect<HookEvent.NowPlayingChanged>()
        val listens = plugin.collect<HookEvent.ListenIngested>()
        val user = UUID.randomUUID()
        val song = UUID.randomUUID()

        publisher.likesChanged(user, EntityType.USER_PLAYLIST, listOf(song))
        publisher.timecodesChanged(user, listOf(song))
        hooks.emit(HookEvent.NowPlayingChanged(user, song, 1, 2))
        hooks.emit(HookEvent.ListenIngested(user, 3))

        assertEquals(HookEvent.LikesChanged(user, EntityType.USER_PLAYLIST, setOf(song)), likes.next())
        assertEquals(HookEvent.TimecodesChanged(user, setOf(song)), timecodes.next())
        assertEquals(HookEvent.NowPlayingChanged(user, song, 1, 2), nowPlaying.next())
        assertEquals(HookEvent.ListenIngested(user, 3), listens.next())
    }

    @Test
    fun `every event and every entity type has a decided group`() {
        val events = HookEvent::class.sealedSubclasses.toSet()

        assertEquals(events, PluginHooks.HOOK_GROUPS_BY_EVENT.keys)
        assertEquals(
            setOf(HookEvent.AlbumsLinkedToMusicBrainz::class),
            PluginHooks.HOOK_GROUPS_BY_EVENT.filterValues { it.isEmpty() }.keys
        )
        assertEquals(
            mapOf(
                EntityType.UNKNOWN to null,
                EntityType.SONG to HookGroup.LIBRARY,
                EntityType.ALBUM to HookGroup.LIBRARY,
                EntityType.ARTIST to HookGroup.LIBRARY,
                EntityType.USER_PLAYLIST to HookGroup.PLAYLISTS,
                EntityType.PLAYLIST to HookGroup.PLAYLISTS,
                EntityType.COLLECTION to HookGroup.PLAYLISTS,
            ),
            EntityType.entries.associateWith { it.hookGroup() }
        )
        assertEquals(
            setOf(HookGroup.LIBRARY, HookGroup.PLAYLISTS),
            PluginHooks.HOOK_GROUPS_BY_EVENT[HookEvent.EntitiesMerged::class]
        )
        assertEquals(HookGroup.entries.toSet(), PluginHooks.HOOK_GROUPS_BY_EVENT.values.flatten().toSet())
    }

    @Test
    fun `an event of an unknown entity type reaches no plugin`() = runBlocking {
        val plugin = hooks.forPlugin("all", HookGroup.entries.toSet())
        val events = plugin.collect<HookEvent.EntitiesUpdated>()

        hooks.publish(HookEvent.EntitiesUpdated(EntityType.UNKNOWN, setOf(UUID.randomUUID())))

        events.assertNothingMore()
    }

    @Test
    fun `a plugin cannot emit an event`() = runBlocking {
        val plugin = hooks.forPlugin("forger", HookGroup.entries.toSet())
        val internal = Channel<HookEvent>(Channel.UNLIMITED)
        hooks.on<HookEvent.EntitiesDeleted> { internal.send(it) }
        hooks.on<HookEvent.PlaylistChanged> { internal.send(it) }

        val deleted = assertThrows<UnsupportedOperationException> {
            runBlocking { plugin.emit(HookEvent.EntitiesDeleted(EntityType.SONG, setOf(UUID.randomUUID()))) }
        }
        assertThrows<UnsupportedOperationException> {
            runBlocking { plugin.emit(HookEvent.PlaylistChanged(UUID.randomUUID())) }
        }

        assertEquals(
            "Plugin forger cannot emit EntitiesDeleted, hook events are announced by the server only",
            deleted.message
        )
        internal.assertNothingMore()
    }

    @Test
    fun `a blocked plugin handler delays neither the operation nor other handlers`() = runBlocking {
        val blocked = hooks.forPlugin("blocked", setOf(HookGroup.LIBRARY))
        val other = hooks.forPlugin("other", setOf(HookGroup.LIBRARY))
        val entered = CompletableDeferred<Unit>()
        blocked.on<HookEvent.EntitiesCreated> {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pluginEvents = other.collect<HookEvent.EntitiesCreated>()
        val internalEvents = Channel<HookEvent.EntitiesCreated>(Channel.UNLIMITED)
        hooks.on<HookEvent.EntitiesCreated> { internalEvents.send(it) }
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        withTimeout(2.seconds) { publisher.created(EntityType.SONG, listOf(first)) }
        withTimeout(2.seconds) { entered.await() }
        withTimeout(2.seconds) { publisher.created(EntityType.SONG, listOf(second)) }

        assertEquals(setOf(first, second), setOf(pluginEvents.next(), pluginEvents.next()).flatMapTo(mutableSetOf()) { it.ids })
        assertEquals(setOf(first, second), setOf(internalEvents.next(), internalEvents.next()).flatMapTo(mutableSetOf()) { it.ids })
    }

    @Test
    fun `a failing plugin handler is isolated`() = runBlocking {
        val failing = hooks.forPlugin("failing", setOf(HookGroup.PLAYLISTS))
        val other = hooks.forPlugin("other", setOf(HookGroup.PLAYLISTS))
        val attempts = Channel<HookEvent.PlaylistChanged>(Channel.UNLIMITED)
        failing.on<HookEvent.PlaylistChanged> {
            attempts.send(it)
            throw IllegalStateException("boom")
        }
        val pluginEvents = other.collect<HookEvent.PlaylistChanged>()
        val internalEvents = Channel<HookEvent.PlaylistChanged>(Channel.UNLIMITED)
        hooks.on<HookEvent.PlaylistChanged> { internalEvents.send(it) }
        val first = HookEvent.PlaylistChanged(UUID.randomUUID())
        val second = HookEvent.PlaylistChanged(UUID.randomUUID())

        hooks.emit(first)
        assertEquals(first, attempts.next())
        assertEquals(first, pluginEvents.next())
        assertEquals(first, internalEvents.next())
        hooks.emit(second)
        assertEquals(second, pluginEvents.next())
        assertEquals(second, internalEvents.next())
        assertEquals(second, attempts.next())
    }

    @Test
    fun `registrations are listed per plugin and a cancelled one is no longer listed`() {
        val first = hooks.forPlugin("first", setOf(HookGroup.LIBRARY, HookGroup.PLAYLISTS))
        val second = hooks.forPlugin("second", setOf(HookGroup.PLAYBACK))
        first.on<HookEvent.EntitiesCreated> { }
        val registration = first.on<HookEvent.PlaylistChanged> { }
        first.on<HookEvent.LibraryIndexed> { }
        second.on<HookEvent.ListenIngested> { }

        assertEquals(
            listOf(HookEvent.EntitiesCreated::class, HookEvent.PlaylistChanged::class, HookEvent.LibraryIndexed::class),
            hooks.pluginRegistrations("first")
        )
        assertEquals(listOf(HookEvent.ListenIngested::class), hooks.pluginRegistrations("second"))
        assertEquals(emptyList<Any>(), hooks.pluginRegistrations("unknown"))

        registration.cancel()

        assertEquals(
            listOf(HookEvent.EntitiesCreated::class, HookEvent.LibraryIndexed::class),
            hooks.pluginRegistrations("first")
        )
    }

    @Test
    fun `a cancelled registration receives nothing`() = runBlocking {
        val plugin = hooks.forPlugin("plugin", setOf(HookGroup.PLAYLISTS))
        val cancelled = Channel<HookEvent.PlaylistChanged>(Channel.UNLIMITED)
        val registration = plugin.on<HookEvent.PlaylistChanged> { cancelled.send(it) }
        val kept = plugin.collect<HookEvent.PlaylistChanged>()
        val event = HookEvent.PlaylistChanged(UUID.randomUUID())

        registration.cancel()
        hooks.emit(event)

        assertEquals(event, kept.next())
        cancelled.assertNothingMore()
    }

    @Test
    fun `removing a plugin removes its registrations and nothing is delivered afterwards`() = runBlocking {
        val removed = hooks.forPlugin("removed", setOf(HookGroup.LIBRARY, HookGroup.PLAYLISTS))
        val kept = hooks.forPlugin("kept", setOf(HookGroup.PLAYLISTS))
        val removedEvents = Channel<HookEvent>(Channel.UNLIMITED)
        removed.on<HookEvent.PlaylistChanged> { removedEvents.send(it) }
        removed.on<HookEvent.EntitiesDeleted> { removedEvents.send(it) }
        val keptEvents = kept.collect<HookEvent.PlaylistChanged>()
        val before = HookEvent.PlaylistChanged(UUID.randomUUID())
        val after = HookEvent.PlaylistChanged(UUID.randomUUID())

        hooks.emit(before)
        assertEquals(before, removedEvents.next())
        assertEquals(before, keptEvents.next())

        hooks.removePlugin("removed")
        hooks.emit(after)
        publisher.deleting(EntityType.SONG, listOf(UUID.randomUUID()))

        assertEquals(after, keptEvents.next())
        removedEvents.assertNothingMore()
        assertEquals(emptyList<Any>(), hooks.pluginRegistrations("removed"))
        assertEquals(listOf(HookEvent.PlaylistChanged::class), hooks.pluginRegistrations("kept"))
        assertThrows<IllegalStateException> { removed.on<HookEvent.PlaylistChanged> { } }
        assertEquals(emptyList<Any>(), hooks.pluginRegistrations("removed"))
    }

    @Test
    fun `a plugin loaded again replaces the registrations of its previous facade`() = runBlocking {
        val previous = hooks.forPlugin("plugin", setOf(HookGroup.PLAYLISTS))
        val previousEvents = previous.collect<HookEvent.PlaylistChanged>()
        val current = hooks.forPlugin("plugin", setOf(HookGroup.PLAYLISTS))
        val currentEvents = current.collect<HookEvent.CollectionChanged>()
        val collection = HookEvent.CollectionChanged(UUID.randomUUID())

        hooks.emit(HookEvent.PlaylistChanged(UUID.randomUUID()))
        hooks.emit(collection)

        assertEquals(collection, currentEvents.next())
        previousEvents.assertNothingMore()
        assertEquals(listOf(HookEvent.CollectionChanged::class), hooks.pluginRegistrations("plugin"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `events reach a plugin only after the commit and outside the transaction`(dialect: DbDialect) = runBlocking {
        val database = connect(dialect)
        val plugin = hooks.forPlugin("plugin", setOf(HookGroup.LIBRARY))
        val seen = CompletableDeferred<Pair<Set<UUID>, Transaction?>>()
        plugin.on<HookEvent.EntitiesDeleted> { seen.complete(markers(database) to TransactionManager.currentOrNull()) }
        val song = UUID.randomUUID()

        transaction(database) {
            PluginProbeTable.insert { it[marker] = song }
            publisher.deleting(EntityType.SONG, listOf(song))
            Thread.sleep(200)
            assertFalse(seen.isCompleted)
        }

        val (markers, handlerTransaction) = withTimeout(2.seconds) { seen.await() }
        assertEquals(setOf(song), markers)
        assertNull(handlerTransaction)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `nothing reaches a plugin after a rollback`(dialect: DbDialect) = runBlocking {
        val database = connect(dialect)
        val plugin = hooks.forPlugin("plugin", setOf(HookGroup.LIBRARY))
        val events = plugin.collect<HookEvent.EntitiesCreated>()
        val committed = UUID.randomUUID()

        assertThrows<IllegalArgumentException> {
            transaction(database) {
                publisher.created(EntityType.SONG, listOf(UUID.randomUUID()))
                throw IllegalArgumentException("caller fails")
            }
        }
        transaction(database) {
            publisher.created(EntityType.ALBUM, listOf(UUID.randomUUID()))
            rollback()
        }
        transaction(database) { publisher.created(EntityType.SONG, listOf(committed)) }

        assertEquals(HookEvent.EntitiesCreated(EntityType.SONG, setOf(committed)), events.next())
        events.assertNothingMore()
    }

    private fun connect(dialect: DbDialect): Database {
        val database = TestDatabase.connect(dialect, "plugin_hooks_test")
        transaction(database) { SchemaUtils.create(PluginProbeTable) }
        return database
    }

    private fun markers(database: Database): Set<UUID> = transaction(database) {
        PluginProbeTable.selectAll().mapTo(mutableSetOf()) { it[PluginProbeTable.marker] }
    }
}
