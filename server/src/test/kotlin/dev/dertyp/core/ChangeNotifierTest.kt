package dev.dertyp.core

import dev.dertyp.data.Change
import dev.dertyp.data.ChangeTopic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ChangeNotifierTest {
    private val notifier = ChangeNotifier()
    private val window = ChangeNotifier.COALESCE_WINDOW

    private fun TestScope.observe(userId: UUID, received: Channel<Change>): Job {
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            notifier.observe(userId).collect { received.send(it) }
        }
        runCurrent()
        return job
    }

    private fun TestScope.passWindow() {
        advanceTimeBy(window)
        runCurrent()
    }

    private fun Channel<Change>.drain(): List<Change> = generateSequence { tryReceive().getOrNull() }.toList()

    private fun entries(): Map<*, *> {
        val channels =
            ChangeNotifier::class.java.getDeclaredField("channels").apply { isAccessible = true }.get(notifier)
        return PerUserChannels::class.java.getDeclaredField("entries").apply { isAccessible = true }
            .get(channels) as Map<*, *>
    }

    @Test
    fun `a subscriber only receives the changes of its own user`() = runTest {
        val user = UUID.randomUUID()
        val other = UUID.randomUUID()
        val received = Channel<Change>(Channel.UNLIMITED)
        val otherReceived = Channel<Change>(Channel.UNLIMITED)
        val job = observe(user, received)
        val otherJob = observe(other, otherReceived)

        notifier.notify(user, ChangeTopic.HOME_CARDS)
        passWindow()

        assertEquals(listOf(Change(ChangeTopic.HOME_CARDS)), received.drain())
        assertTrue(otherReceived.drain().isEmpty())
        job.cancelAndJoin()
        otherJob.cancelAndJoin()
    }

    @Test
    fun `a burst of one topic within the window arrives as a single change`() = runTest {
        val user = UUID.randomUUID()
        val received = Channel<Change>(Channel.UNLIMITED)
        val job = observe(user, received)

        repeat(5) { notifier.notify(user, ChangeTopic.LISTENS) }
        advanceTimeBy(window - 1.milliseconds)
        runCurrent()
        assertNull(received.tryReceive().getOrNull())

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(listOf(Change(ChangeTopic.LISTENS)), received.drain())

        notifier.notify(user, ChangeTopic.LISTENS)
        passWindow()
        assertEquals(listOf(Change(ChangeTopic.LISTENS)), received.drain())
        job.cancelAndJoin()
    }

    @Test
    fun `different topics within the window are all delivered`() = runTest {
        val user = UUID.randomUUID()
        val received = Channel<Change>(Channel.UNLIMITED)
        val job = observe(user, received)

        notifier.notify(user, ChangeTopic.HOME_CARDS)
        notifier.notify(user, ChangeTopic.LISTENS)
        notifier.notify(user, ChangeTopic.HOME_CARDS)
        notifier.notify(user, ChangeTopic.ONLINE_DEVICES)
        passWindow()

        val changes = received.drain()
        assertEquals(3, changes.size)
        assertEquals(
            setOf(ChangeTopic.HOME_CARDS, ChangeTopic.LISTENS, ChangeTopic.ONLINE_DEVICES),
            changes.map { it.topic }.toSet()
        )
        job.cancelAndJoin()
    }

    @Test
    fun `changes without a subscriber are dropped and not replayed`() = runTest {
        val user = UUID.randomUUID()

        notifier.notify(user, ChangeTopic.LISTENBRAINZ_STATUS)
        assertTrue(entries().isEmpty())

        val received = Channel<Change>(Channel.UNLIMITED)
        val job = observe(user, received)
        passWindow()

        assertTrue(received.drain().isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun `the channel of a user is released once the last subscriber leaves`() = runTest {
        val user = UUID.randomUUID()
        val first = Channel<Change>(Channel.UNLIMITED)
        val second = Channel<Change>(Channel.UNLIMITED)
        val firstJob = observe(user, first)
        val secondJob = observe(user, second)
        assertEquals(1, entries().size)

        firstJob.cancelAndJoin()
        assertEquals(1, entries().size)
        notifier.notify(user, ChangeTopic.ONLINE_DEVICES)
        passWindow()
        assertEquals(listOf(Change(ChangeTopic.ONLINE_DEVICES)), second.drain())
        assertTrue(first.drain().isEmpty())

        secondJob.cancelAndJoin()
        assertTrue(entries().isEmpty())
    }
}
