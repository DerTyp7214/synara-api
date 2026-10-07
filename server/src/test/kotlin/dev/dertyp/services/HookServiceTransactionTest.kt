package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.db.dbQuery
import dev.dertyp.data.EntityType
import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.on
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.Database
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
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HookServiceTransactionTest {
    private object HookProbeTable : Table("hook_probe") {
        val marker = javaUUID("marker")
    }

    private class Announcement(val subscriber: String, val call: String, val transaction: Transaction?, val thread: Thread)

    private class RecordingSubscriber(
        private val name: String,
        private val announcements: MutableList<Announcement>
    ) : EntityWriteSubscriber {
        private fun record(call: String) {
            announcements += Announcement(name, call, TransactionManager.currentOrNull(), Thread.currentThread())
        }

        override fun created(type: EntityType, ids: Collection<UUID>) = record("created $type ${ids.toList()}")

        override fun updated(type: EntityType, ids: Collection<UUID>, containersChanged: Boolean) =
            record("updated $type ${ids.toList()} $containersChanged")

        override fun relinked(type: EntityType, ids: Collection<UUID>) = record("relinked $type ${ids.toList()}")

        override fun leavingContainers(type: EntityType, ids: Collection<UUID>) =
            record("leavingContainers $type ${ids.toList()}")

        override fun membersChanged(type: EntityType, ids: Collection<UUID>) =
            record("membersChanged $type ${ids.toList()}")

        override fun deleting(type: EntityType, ids: Collection<UUID>) = record("deleting $type ${ids.toList()}")

        override fun merging(type: EntityType, keptId: UUID, removedIds: Collection<UUID>) =
            record("merging $type $keptId ${removedIds.toList()}")

        override fun likesChanged(userId: UUID, type: EntityType, ids: Collection<UUID>) =
            record("likesChanged $userId $type ${ids.toList()}")

        override fun timecodesChanged(userId: UUID, songIds: Collection<UUID>) =
            record("timecodesChanged $userId ${songIds.toList()}")
    }

    private class WritingSubscriber : EntityWriteSubscriber {
        override fun created(type: EntityType, ids: Collection<UUID>) {
            for (entity in ids) HookProbeTable.insert { it[marker] = entity }
        }
    }

    private class FailingSubscriber : EntityWriteSubscriber {
        override fun deleting(type: EntityType, ids: Collection<UUID>) = throw IllegalStateException("boom")
    }

    private lateinit var database: Database
    private val hooks = HookService()
    private val publisher = EntityEventPublisher(hooks)

    private fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "hook_service_transaction_test", HookProbeTable)
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    private fun markers(): Set<UUID> = transaction(database) {
        HookProbeTable.selectAll().mapTo(mutableSetOf()) { it[HookProbeTable.marker] }
    }

    private fun insertMarker(value: UUID) {
        HookProbeTable.insert { it[marker] = value }
    }

    private fun createdEvents(): Channel<HookEvent.EntitiesCreated> {
        val events = Channel<HookEvent.EntitiesCreated>(Channel.UNLIMITED)
        hooks.on<HookEvent.EntitiesCreated> { events.send(it) }
        return events
    }

    private suspend fun <T> Channel<T>.next(): T = withTimeout(2.seconds) { receive() }

    private suspend fun <T> Channel<T>.assertNothingMore() =
        assertNull(withTimeoutOrNull(200.milliseconds) { receive() })

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `in transaction subscribers are called synchronously in registration order inside the transaction`(
        dialect: DbDialect
    ) {
        setup(dialect)
        val announcements = mutableListOf<Announcement>()
        hooks.inTransaction(RecordingSubscriber("first", announcements))
        hooks.inTransaction(RecordingSubscriber("second", announcements))
        val song = UUID.randomUUID()
        val other = UUID.randomUUID()
        val user = UUID.randomUUID()

        transaction(database) {
            publisher.created(EntityType.SONG, listOf(song))
            assertEquals(
                listOf("first" to "created SONG [$song]", "second" to "created SONG [$song]"),
                announcements.map { it.subscriber to it.call }
            )
            assertTrue(announcements.all { it.transaction === this && it.thread === Thread.currentThread() })
            announcements.clear()

            publisher.updated(EntityType.SONG, listOf(song))
            publisher.updated(EntityType.SONG, listOf(song), containersChanged = true)
            publisher.relinked(EntityType.SONG, listOf(song))
            publisher.leavingContainers(EntityType.SONG, listOf(song))
            publisher.membersChanged(EntityType.ALBUM, listOf(other))
            publisher.deleting(EntityType.SONG, listOf(song))
            publisher.merging(EntityType.ARTIST, song, listOf(other, song))
            publisher.likesChanged(user, EntityType.SONG, listOf(song))
            publisher.timecodesChanged(user, listOf(song))
        }

        assertEquals(
            listOf(
                "updated SONG [$song] false",
                "updated SONG [$song] true",
                "relinked SONG [$song]",
                "leavingContainers SONG [$song]",
                "membersChanged ALBUM [$other]",
                "deleting SONG [$song]",
                "merging ARTIST $song [$other]",
                "likesChanged $user SONG [$song]",
                "timecodesChanged $user [$song]",
            ),
            announcements.filter { it.subscriber == "first" }.map { it.call }
        )
        assertEquals(
            announcements.filter { it.subscriber == "first" }.map { it.call },
            announcements.filter { it.subscriber == "second" }.map { it.call }
        )
        assertEquals(
            List(9) { listOf("first", "second") }.flatten(),
            announcements.map { it.subscriber }
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a write of an in transaction subscriber commits and rolls back with the caller`(dialect: DbDialect) {
        setup(dialect)
        hooks.inTransaction(WritingSubscriber())
        val committed = UUID.randomUUID()
        val rolledBack = UUID.randomUUID()

        transaction(database) { publisher.created(EntityType.SONG, listOf(committed)) }
        assertThrows<IllegalArgumentException> {
            transaction(database) {
                publisher.created(EntityType.SONG, listOf(rolledBack))
                throw IllegalArgumentException("caller fails")
            }
        }

        assertEquals(setOf(committed), markers())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an exception from an in transaction subscriber aborts the transaction of the caller`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val announcements = mutableListOf<Announcement>()
        hooks.inTransaction(FailingSubscriber())
        hooks.inTransaction(RecordingSubscriber("after", announcements))
        val deleted = Channel<HookEvent.EntitiesDeleted>(Channel.UNLIMITED)
        hooks.on<HookEvent.EntitiesDeleted> { deleted.send(it) }

        val failure = assertThrows<IllegalStateException> {
            transaction(database) {
                insertMarker(UUID.randomUUID())
                publisher.deleting(EntityType.SONG, listOf(UUID.randomUUID()))
            }
        }

        assertEquals("boom", failure.message)
        assertEquals(emptySet<UUID>(), markers())
        assertTrue(announcements.isEmpty())
        deleted.assertNothingMore()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `after commit events arrive only after the outermost commit`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val seen = CompletableDeferred<Set<UUID>>()
        hooks.on<HookEvent.EntitiesCreated> { seen.complete(markers()) }
        val song = UUID.randomUUID()

        dbQuery {
            dbQuery {
                insertMarker(song)
                publisher.created(EntityType.SONG, listOf(song))
            }
            delay(200.milliseconds)
            assertFalse(seen.isCompleted)
        }

        assertEquals(setOf(song), withTimeout(2.seconds) { seen.await() })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `nothing arrives after a rollback`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val events = createdEvents()

        assertThrows<IllegalArgumentException> {
            runBlocking {
                dbQuery {
                    dbQuery { publisher.created(EntityType.SONG, listOf(UUID.randomUUID())) }
                    throw IllegalArgumentException("caller fails")
                }
            }
        }
        transaction(database) {
            publisher.created(EntityType.SONG, listOf(UUID.randomUUID()))
            rollback()
        }

        events.assertNothingMore()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `nested dbQuery dispatches once`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val events = createdEvents()
        val outer = UUID.randomUUID()
        val inner = UUID.randomUUID()
        val innermost = UUID.randomUUID()

        dbQuery {
            publisher.created(EntityType.SONG, listOf(outer))
            dbQuery {
                publisher.created(EntityType.SONG, listOf(inner))
                transaction { publisher.created(EntityType.SONG, listOf(innermost)) }
            }
        }

        assertEquals(HookEvent.EntitiesCreated(EntityType.SONG, setOf(outer, inner, innermost)), events.next())
        events.assertNothingMore()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a transaction that commits, publishes again and rolls back delivers only the first part`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val events = createdEvents()
        val first = UUID.randomUUID()

        transaction(database) {
            publisher.created(EntityType.SONG, listOf(first))
            commit()
            publisher.created(EntityType.SONG, listOf(UUID.randomUUID()))
            rollback()
        }

        assertEquals(HookEvent.EntitiesCreated(EntityType.SONG, setOf(first)), events.next())
        events.assertNothingMore()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `every commit of a transaction delivers what was announced since the previous one`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val events = createdEvents()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val third = UUID.randomUUID()

        transaction(database) {
            publisher.created(EntityType.SONG, listOf(first))
            commit()
            publisher.created(EntityType.SONG, listOf(second))
            rollback()
            publisher.created(EntityType.SONG, listOf(third))
        }

        assertEquals(
            setOf(
                HookEvent.EntitiesCreated(EntityType.SONG, setOf(first)),
                HookEvent.EntitiesCreated(EntityType.SONG, setOf(third))
            ),
            setOf(events.next(), events.next())
        )
        events.assertNothingMore()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a retried transaction delivers only the attempt that committed`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val events = createdEvents()
        val failedAttempt = UUID.randomUUID()
        val song = UUID.randomUUID()
        var attempts = 0

        transaction(database) {
            maxAttempts = 2
            attempts++
            if (attempts == 1) {
                publisher.created(EntityType.SONG, listOf(failedAttempt))
                throw SQLException("first attempt fails")
            }
            publisher.created(EntityType.SONG, listOf(song))
        }

        assertEquals(2, attempts)
        assertEquals(HookEvent.EntitiesCreated(EntityType.SONG, setOf(song)), events.next())
        events.assertNothingMore()
    }

    @Test
    fun `outside a transaction an event is dispatched immediately`() = runBlocking {
        val announcements = mutableListOf<Announcement>()
        hooks.inTransaction(RecordingSubscriber("only", announcements))
        val events = createdEvents()
        val song = UUID.randomUUID()

        publisher.created(EntityType.SONG, listOf(song, song))

        assertEquals(HookEvent.EntitiesCreated(EntityType.SONG, setOf(song)), events.next())
        assertEquals(listOf("created SONG [$song]"), announcements.map { it.call })
        assertNull(announcements.single().transaction)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `emit inside a transaction is not deferred`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val received = CompletableDeferred<HookEvent.PlaylistChanged>()
        hooks.on<HookEvent.PlaylistChanged> { received.complete(it) }
        val playlist = UUID.randomUUID()

        val inside = dbQuery {
            hooks.emit(HookEvent.PlaylistChanged(playlist))
            withTimeout(2.seconds) { received.await() }
        }

        assertEquals(HookEvent.PlaylistChanged(playlist), inside)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a cancelled in transaction registration stops receiving`(dialect: DbDialect) {
        setup(dialect)
        val announcements = CopyOnWriteArrayList<Announcement>()
        val registration = hooks.inTransaction(RecordingSubscriber("gone", announcements))
        registration.cancel()

        transaction(database) { publisher.created(EntityType.SONG, listOf(UUID.randomUUID())) }

        assertTrue(announcements.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an after commit handler runs outside the publishing transaction`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val handlerTransaction = CompletableDeferred<Transaction?>()
        hooks.on<HookEvent.EntitiesCreated> { handlerTransaction.complete(TransactionManager.currentOrNull()) }

        transaction(database) { publisher.created(EntityType.SONG, listOf(UUID.randomUUID())) }

        assertNull(withTimeout(2.seconds) { handlerTransaction.await() })
    }
}
